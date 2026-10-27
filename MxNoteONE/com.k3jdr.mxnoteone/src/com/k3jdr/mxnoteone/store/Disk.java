package com.k3jdr.mxnoteone.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import javax.crypto.SecretKey;

/**
 * The notebooks' files are read and written on one thread of their own, in order: the window never waits for
 * the disk, and a page is never read while a save of it is still on its way. Files are written through a
 * temporary file and moved into place, so a crash never leaves half a file.
 */
public final class Disk {
    private Disk() {}

    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MxNoteONE disk");
        t.setDaemon(true);
        return t;
    });

    /** Told about a failed write: (what failed, why). */
    public static volatile BiConsumer<String, Throwable> errors = (what, why) -> {};

    public static Future<?> submit(Runnable work) {
        return THREAD.submit(work);
    }

    /** Writes the bytes (sealed with the key when there is one) after everything queued before. */
    public static void write(Path file, byte[] data, SecretKey key, String what) {
        THREAD.submit(() -> {
            try {
                writeNow(file, data, key);
            } catch (Exception e) {
                errors.accept(what, e);
            }
        });
    }

    public static void writeText(Path file, String text, SecretKey key, String what) {
        write(file, text.getBytes(StandardCharsets.UTF_8), key, what);
    }

    public static void writeNow(Path file, byte[] data, SecretKey key) throws IOException, GeneralSecurityException {
        byte[] out = key == null ? data : NoteCrypto.seal(data, key);
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".mx", ".tmp");
        try {
            Files.write(tmp, out);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static byte[] readNow(Path file, SecretKey key) throws IOException, GeneralSecurityException {
        byte[] data = Files.readAllBytes(file);
        return key == null ? data : NoteCrypto.open(data, key);
    }

    /** Waits (at most a few seconds) until every queued write is done — when the window closes. */
    public static void drain() {
        try {
            THREAD.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // what's still queued is written by the daemon thread while it can
        }
    }
}
