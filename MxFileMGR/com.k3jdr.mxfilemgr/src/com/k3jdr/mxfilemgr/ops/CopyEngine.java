package com.k3jdr.mxfilemgr.ops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.win.Win32;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.ptr.IntByReference;

/**
 * Copies or moves files and folders (FileOperations.swift's CopyEngine). Folders that already exist at the
 * destination are merged; conflicts on files follow the policy, asking when it is Ask. File data is copied with
 * CopyFileEx — attributes, dates and alternate streams kept, with progress and Stop — the way copyfile(3) does on
 * the Mac. A move within one drive is a rename; across drives each source is removed only once it was copied
 * completely. Copying an item onto itself makes an Explorer-style "name - Copy" duplicate.
 */
public final class CopyEngine {
    private final boolean move;
    private final ProgressReporter reporter;
    private ConflictPolicy policy;

    public CopyEngine(boolean move, ConflictPolicy policy, ProgressReporter reporter) {
        this.move = move;
        this.policy = policy;
        this.reporter = reporter;
    }

    public void run(List<Path[]> pairs) {
        reporter.setTotal(FileOps.totals(TransferManager.sources(pairs), reporter::isCancelled)[0]);
        for (Path[] pair : pairs) {
            if (reporter.isCancelled()) return;
            try {
                if (pair[1].getParent() != null) Files.createDirectories(pair[1].getParent());
            } catch (IOException e) {
                reporter.error(FileOps.name(pair[1].getParent()) + ": " + TransferManager.message(e));
                continue;
            }
            transfer(pair[0], pair[1]);
        }
    }

    private static String key(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    /** Returns true when {@code src} was transferred completely (so a move may remove it). */
    private boolean transfer(Path src, Path destination) {
        if (reporter.isCancelled()) return false;
        Path dst = destination;
        boolean srcIsDir = FileOps.isRealDirectory(src);
        String sp = key(src), dp = key(dst);
        if (sp.equals(dp)) {
            if (move) return true;                   // moving onto itself: nothing to do
            dst = FileOps.keepBoth(dst);             // copying onto itself: duplicate
        } else if (srcIsDir && dp.startsWith(sp + "\\")) {
            reporter.error("Can't copy “" + FileOps.name(src) + "” into itself.");
            return false;
        } else if (FileOps.exists(dst)) {
            if (srcIsDir && FileOps.isRealDirectory(dst)) return mergeContents(src, dst);
            switch (resolve(src, dst)) {
                case SKIP -> {
                    reporter.add(FileOps.totals(List.of(src), reporter::isCancelled)[0]);
                    return false;
                }
                case CANCEL -> {
                    reporter.cancel();
                    return false;
                }
                case KEEP_BOTH -> dst = FileOps.keepBoth(dst);
                case OVERWRITE -> {
                    try {
                        deleteRecursively(dst, null);
                    } catch (IOException e) {
                        reporter.error(FileOps.name(dst) + ": " + TransferManager.message(e));
                        return false;
                    }
                }
            }
        }

        // A move within one drive is a rename.
        if (move && FileOps.sameVolume(src, dst)) {
            reporter.begin(FileOps.name(src));
            try {
                long bytes = FileOps.totals(List.of(src), reporter::isCancelled)[0];
                Files.move(src, dst);
                reporter.add(bytes);
                return true;
            } catch (IOException e) {
                reporter.error(FileOps.name(src) + ": " + TransferManager.message(e));
                return false;
            }
        }

        if (srcIsDir) {
            try {
                Files.createDirectory(dst);
            } catch (IOException e) {
                reporter.error(FileOps.name(dst) + ": " + TransferManager.message(e));
                return false;
            }
            boolean ok = mergeContents(src, dst);
            copyFolderMetadata(src, dst);
            return ok;
        }

        boolean ok = isLink(src) ? copyLink(src, dst) : copyFile(src, dst);
        if (!ok) return false;
        if (move) {
            try {
                deleteRecursively(src, null);
            } catch (IOException e) {
                reporter.error(FileOps.name(src) + ": " + TransferManager.message(e));
                return false;
            }
        }
        return true;
    }

    private boolean mergeContents(Path src, Path dst) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            for (Path c : ds) children.add(c);
        } catch (IOException e) {
            reporter.error(FileOps.name(src) + ": " + TransferManager.message(e));
            return false;
        }
        boolean ok = true;
        for (Path child : children) {
            if (reporter.isCancelled()) return false;
            if (!transfer(child, dst.resolve(child.getFileName().toString()))) ok = false;
        }
        if (ok && move) {
            try {
                clearReadOnly(src);
                Files.delete(src);
            } catch (IOException e) {
                reporter.error(FileOps.name(src) + ": " + TransferManager.message(e));
                return false;
            }
        }
        return ok;
    }

    private ConflictPolicy.Decision resolve(Path src, Path dst) {
        return switch (policy) {
            case OVERWRITE -> ConflictPolicy.Decision.OVERWRITE;
            case SKIP -> ConflictPolicy.Decision.SKIP;
            case KEEP_BOTH -> ConflictPolicy.Decision.KEEP_BOTH;
            case OVERWRITE_OLDER -> FileOps.modified(src) > FileOps.modified(dst) ? ConflictPolicy.Decision.OVERWRITE
                    : ConflictPolicy.Decision.SKIP;
            case ASK -> {
                ConflictPrompt.Answer a = ConflictPrompt.ask(src, dst);
                if (a.applyToAll()) {
                    switch (a.decision()) {
                        case OVERWRITE -> policy = ConflictPolicy.OVERWRITE;
                        case SKIP -> policy = ConflictPolicy.SKIP;
                        case KEEP_BOTH -> policy = ConflictPolicy.KEEP_BOTH;
                        default -> { }
                    }
                }
                yield a.decision();
            }
        };
    }

    private static boolean isLink(Path p) {
        try {
            return FileOps.isLink(p, Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            return false;
        }
    }

    /** Symbolic links are copied as links (Windows needs Developer Mode or admin rights to create them). */
    private boolean copyLink(Path src, Path dst) {
        reporter.begin(FileOps.name(src));
        try {
            Files.copy(src, dst, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            reporter.error(FileOps.name(src) + ": a link can't be copied here (" + TransferManager.message(e) + ")");
            return false;
        }
    }

    private boolean copyFile(Path src, Path dst) {
        reporter.begin(FileOps.name(src));
        long size = FileOps.size(src);
        Win32.Kernel k = Win32.kernel();
        if (k != null) {
            Win32.CopyProgress progress = (total, transferred, streamSize, streamTransferred, stream, reason, s, d, data) -> {
                reporter.fileProgress(transferred);
                return reporter.isCancelled() ? 1 : 0;
            };
            IntByReference cancel = new IntByReference(0);
            boolean ok = k.CopyFileExW(new WString(Win32.longPath(src.toString())), new WString(Win32.longPath(dst.toString())),
                    progress, null, cancel, 0);
            if (ok) {
                reporter.add(size);
                return true;
            }
            int err = Native.getLastError();
            if (reporter.isCancelled() || err == 1235 /* ERROR_REQUEST_ABORTED */) return false;
            reporter.error(FileOps.name(src) + ": " + describe(err));
            return false;
        }
        return copyFileJava(src, dst, size);
    }

    private static String describe(int err) {
        try {
            return Kernel32Util.formatMessage(err).trim();
        } catch (Throwable t) {
            return "Windows error " + err;
        }
    }

    /** The fallback when JNA isn't there: a chunked copy with progress, then the dates and attributes. */
    private boolean copyFileJava(Path src, Path dst, long size) {
        try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
                FileChannel out = FileChannel.open(dst, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
            long copied = 0;
            while (in.read(buf) >= 0) {
                if (reporter.isCancelled()) throw new IOException("Stopped");
                buf.flip();
                while (buf.hasRemaining()) copied += out.write(buf);
                buf.clear();
                reporter.fileProgress(copied);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(dst);
            } catch (IOException ignored) {
                // the partial file stays
            }
            if (!reporter.isCancelled()) reporter.error(FileOps.name(src) + ": " + TransferManager.message(e));
            return false;
        }
        try {
            BasicFileAttributes a = Files.readAttributes(src, BasicFileAttributes.class);
            Files.setLastModifiedTime(dst, a.lastModifiedTime());
            Files.setAttribute(dst, "basic:creationTime", a.creationTime());
        } catch (IOException ignored) {
            // the data is there
        }
        reporter.add(size);
        return true;
    }

    private static void copyFolderMetadata(Path src, Path dst) {
        try {
            DosFileAttributes a = Files.readAttributes(src, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Files.setLastModifiedTime(dst, a.lastModifiedTime());
            Files.setAttribute(dst, "basic:creationTime", a.creationTime());
            if (a.isHidden()) Files.setAttribute(dst, "dos:hidden", true);
            if (a.isReadOnly()) Files.setAttribute(dst, "dos:readonly", true);
        } catch (IOException ignored) {
            // the contents are there
        }
    }

    private static void clearReadOnly(Path p) {
        try {
            if ((Boolean) Files.getAttribute(p, "dos:readonly", LinkOption.NOFOLLOW_LINKS)) {
                Files.setAttribute(p, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (Exception ignored) {
            // the delete reports it
        }
    }

    /** Deletes a file, a link (not what it points to) or a folder with everything in it, for good. */
    public static void deleteRecursively(Path p, ProgressReporter reporter) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isDirectory() || FileOps.isLink(p, a)) {
            clearReadOnly(p);
            Files.delete(p);
            return;
        }
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (reporter != null && reporter.isCancelled()) return FileVisitResult.TERMINATE;
                if (!dir.equals(p) && FileOps.isLink(dir, attrs)) {
                    Files.delete(dir);           // a junction: remove the link, not its target's contents
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (reporter != null && reporter.isCancelled()) return FileVisitResult.TERMINATE;
                clearReadOnly(file);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                clearReadOnly(dir);
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
