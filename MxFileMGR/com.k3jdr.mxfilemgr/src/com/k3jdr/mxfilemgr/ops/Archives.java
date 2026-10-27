package com.k3jdr.mxfilemgr.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.k3jdr.mxfilemgr.model.FileOps;

/**
 * Compress and Extract (the macOS app's ditto, zip and tar): zip archives with java.util.zip; tar, tgz, tbz, txz,
 * 7z, rar, cpio, xar and iso with Windows' own tar.exe (bsdtar / libarchive).
 */
public final class Archives {
    private Archives() {}

    /** Writes {@code items} into a new zip, each under its own name (folders with everything in them). */
    public static void zip(List<Path> items, Path archive, ProgressReporter reporter) {
        reporter.setTotal(FileOps.totals(items, reporter::isCancelled)[0]);
        reporter.begin(FileOps.name(archive));
        boolean ok = false;
        try (OutputStream os = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            byte[] buf = new byte[1 << 16];
            for (Path item : items) {
                if (reporter.isCancelled()) break;
                Path base = item.getParent();
                Files.walkFileTree(item, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (reporter.isCancelled()) return FileVisitResult.TERMINATE;
                        if (!dir.equals(item) && FileOps.isLink(dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                        ZipEntry e = new ZipEntry(entryName(base, dir) + "/");
                        e.setLastModifiedTime(attrs.lastModifiedTime());
                        zip.putNextEntry(e);
                        zip.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (reporter.isCancelled()) return FileVisitResult.TERMINATE;
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        reporter.begin(FileOps.name(file));
                        ZipEntry e = new ZipEntry(entryName(base, file));
                        e.setLastModifiedTime(attrs.lastModifiedTime());
                        zip.putNextEntry(e);
                        long done = 0;
                        try (InputStream in = Files.newInputStream(file)) {
                            for (int n; (n = in.read(buf)) > 0;) {
                                zip.write(buf, 0, n);
                                done += n;
                                reporter.fileProgress(done);
                                if (reporter.isCancelled()) return FileVisitResult.TERMINATE;
                            }
                        }
                        zip.closeEntry();
                        reporter.add(attrs.size());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        reporter.error(FileOps.name(file) + ": " + TransferManager.message(exc));
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            ok = !reporter.isCancelled();
        } catch (IOException e) {
            reporter.error(FileOps.name(archive) + ": " + TransferManager.message(e));
        }
        if (!ok) {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // the partial archive stays
            }
        }
    }

    private static String entryName(Path base, Path p) {
        Path rel = base == null ? p.getFileName() : base.relativize(p);
        return rel.toString().replace('\\', '/');
    }

    /** Unpacks {@code archive} into the folder {@code target} (which exists). */
    public static void extract(Path archive, Path target, ProgressReporter reporter) {
        if (FileOps.extension(archive).toLowerCase(Locale.ROOT).equals("zip")) {
            try {
                unzip(archive, target, StandardCharsets.UTF_8, reporter);
                return;
            } catch (IllegalArgumentException e) {
                // names that aren't UTF-8: an older zip in the DOS code page
            } catch (IOException e) {
                reporter.error(FileOps.name(archive) + ": " + TransferManager.message(e));
                return;
            }
            try {
                unzip(archive, target, Charset.forName("IBM437"), reporter);
            } catch (IOException e) {
                reporter.error(FileOps.name(archive) + ": " + TransferManager.message(e));
            }
            return;
        }
        Path tar = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "tar.exe");
        CLITool.runJob(Files.isRegularFile(tar) ? tar.toString() : "tar.exe",
                List.of("-xf", archive.toString(), "-C", target.toString()), null, reporter, null);
    }

    private static void unzip(Path archive, Path target, Charset charset, ProgressReporter reporter) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(archive.toFile(), charset)) {
            int count = zip.size(), i = 0;
            byte[] buf = new byte[1 << 16];
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements() && !reporter.isCancelled();) {
                ZipEntry e = en.nextElement();
                reporter.step(i++, count, e.getName());
                Path out = root.resolve(e.getName().replace('/', '\\')).normalize();
                if (!out.startsWith(root)) {
                    reporter.error(e.getName() + ": outside the target folder, skipped");
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e); OutputStream os = Files.newOutputStream(out)) {
                    for (int n; (n = in.read(buf)) > 0;) os.write(buf, 0, n);
                }
                if (e.getLastModifiedTime() != null) Files.setLastModifiedTime(out, e.getLastModifiedTime());
                else if (e.getTime() > 0) Files.setLastModifiedTime(out, FileTime.fromMillis(e.getTime()));
            }
        }
    }
}
