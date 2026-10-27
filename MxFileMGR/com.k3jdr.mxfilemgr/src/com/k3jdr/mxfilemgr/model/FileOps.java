package com.k3jdr.mxfilemgr.model;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.k3jdr.mxfilemgr.win.Win32;
import com.sun.jna.WString;

/** File helpers shared by the operations and the tools (FileOperations.swift's FileOps). */
public final class FileOps {
    private FileOps() {}

    /** Archives Extract unpacks: zip with java.util.zip, the rest with Windows' tar.exe (libarchive). */
    public static final Set<String> EXTRACTABLE = Set.of("zip", "tar", "tgz", "gz", "tbz", "tbz2", "bz2", "txz", "xz",
            "7z", "rar", "cpio", "xar", "iso", "lzma", "zst");

    public static boolean isArchive(Path p) {
        return EXTRACTABLE.contains(extension(p).toLowerCase(Locale.ROOT));
    }

    public static String extension(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1) : "";
    }

    public static String name(Path p) {
        if (p == null) return "This PC";
        Path f = p.getFileName();
        return f == null ? p.toString() : f.toString();
    }

    /** "photos.tar.gz" → "photos". */
    public static String archiveStem(Path p) {
        String n = name(p);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        if (n.toLowerCase(Locale.ROOT).endsWith(".tar")) n = n.substring(0, n.length() - 4);
        return n;
    }

    /** True for a directory that is not a symbolic link or junction. */
    public static boolean isRealDirectory(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isDirectory() && !isLink(p, a);
        } catch (IOException e) {
            return false;
        }
    }

    /** A symbolic link, or a junction (a directory reparse point that resolves elsewhere). */
    public static boolean isLink(Path p, BasicFileAttributes a) {
        if (a.isSymbolicLink()) return true;
        if (a.isDirectory() && a.isOther()) {
            Win32.Kernel k = Win32.kernel();
            if (k != null) {
                Win32.FindData d = new Win32.FindData();
                var h = k.FindFirstFileExW(new WString(Win32.longPath(p.toString())), 1, d, 0, null, 0);
                if (!Win32.isInvalid(h)) {
                    k.FindClose(h);
                    return (d.dwFileAttributes & Win32.FILE_ATTRIBUTE_REPARSE_POINT) != 0
                            && (d.dwReserved0 == Win32.IO_REPARSE_TAG_MOUNT_POINT || d.dwReserved0 == Win32.IO_REPARSE_TAG_SYMLINK);
                }
            }
        }
        return false;
    }

    /** True if anything (even a dangling link) is at {@code p}. */
    public static boolean exists(Path p) {
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
    }

    public static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Both on one drive or share, so a move is a rename. */
    public static boolean sameVolume(Path a, Path b) {
        Path ra = a.toAbsolutePath().getRoot(), rb = b.toAbsolutePath().getRoot();
        return ra != null && rb != null && ra.toString().equalsIgnoreCase(rb.toString());
    }

    /** Total bytes of regular files, and number of entries, below {@code paths} (links are not followed). */
    public static long[] totals(List<Path> paths, BooleanSupplier cancelled) {
        long[] t = new long[2];
        for (Path p : paths) {
            if (cancelled != null && cancelled.getAsBoolean()) break;
            t[1]++;
            if (!isRealDirectory(p)) {
                t[0] += size(p);
                continue;
            }
            try {
                Files.walkFileTree(p, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (cancelled != null && cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                        if (!dir.equals(p)) {
                            t[1]++;
                            if (isLink(dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        t[1]++;
                        if (attrs.isRegularFile()) t[0] += attrs.size();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // what could be counted is counted
            }
        }
        return t;
    }

    /** Explorer-style "name - Copy.ext", "name - Copy (2).ext", … next to {@code p}. */
    public static Path keepBoth(Path p) {
        Path dir = p.getParent();
        String n = name(p);
        boolean folder = isRealDirectory(p);
        int dot = folder ? -1 : n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        String ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 1;; i++) {
            String candidate = stem + " - Copy" + (i == 1 ? "" : " (" + i + ")") + ext;
            Path c = dir.resolve(candidate);
            if (!exists(c)) return c;
        }
    }

    /** "stem.ext", or "stem (2).ext", "stem (3).ext", … if taken (Explorer's new-item names). */
    public static Path available(Path dir, String stem, String ext) {
        for (int i = 1;; i++) {
            String n = (i == 1 ? stem : stem + " (" + i + ")") + (ext == null || ext.isEmpty() ? "" : "." + ext);
            Path c = dir.resolve(n);
            if (!exists(c)) return c;
        }
    }

    /** A name Windows accepts for a file or folder: not empty, none of \ / : * ? " < > |, no trailing dot or space. */
    public static String nameProblem(String name) {
        if (name == null || name.isBlank()) return "Enter a name.";
        for (char c : name.toCharArray()) {
            if ("\\/:*?\"<>|".indexOf(c) >= 0 || c < 32) return "A name can't contain \\ / : * ? \" < > |";
        }
        if (name.endsWith(".") || name.endsWith(" ")) return "A name can't end with a dot or a space.";
        String upper = name.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        String base = dot >= 0 ? upper.substring(0, dot) : upper;
        if (Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3").contains(base)) {
            return "“" + name + "” is reserved by Windows.";
        }
        return null;
    }
}
