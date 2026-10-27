package com.k3jdr.mxfilemgr.model;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.List;

import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.win.Win32;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

/**
 * Lists a folder (FileModels.swift's DirectoryLoader): one FindFirstFileEx pass gives each entry's name, size,
 * dates, attributes and reparse tag, so links, junctions and cloud placeholders are told apart without touching
 * them; java.nio is the fallback. "This PC" ({@code null}) lists the drives.
 */
public final class DirectoryLoader {
    private DirectoryLoader() {}

    public static List<FileItem> load(Path dir, boolean showHidden) throws IOException {
        if (dir == null) return drives();
        List<FileItem> fast = loadWin32(dir, showHidden);
        return fast != null ? fast : loadNio(dir, showHidden);
    }

    private static List<FileItem> loadWin32(Path dir, boolean showHidden) {
        Win32.Kernel k = Win32.kernel();
        if (k == null) return null;
        String base = dir.toString();
        String pattern = Win32.longPath(base.endsWith("\\") ? base + "*" : base + "\\*");
        Win32.FindData d = new Win32.FindData();
        Pointer h = k.FindFirstFileExW(new WString(pattern), 1 /* FindExInfoBasic */, d, 0, null, 2 /* LARGE_FETCH */);
        if (Win32.isInvalid(h)) return null;     // let java.nio report why
        List<FileItem> out = new ArrayList<>();
        try {
            do {
                String name = d.name();
                if (name.equals(".") || name.equals("..")) continue;
                int a = d.dwFileAttributes;
                boolean hidden = (a & Win32.FILE_ATTRIBUTE_HIDDEN) != 0;
                if (hidden && !showHidden) continue;
                boolean reparse = (a & Win32.FILE_ATTRIBUTE_REPARSE_POINT) != 0;
                boolean link = reparse && (d.dwReserved0 == Win32.IO_REPARSE_TAG_SYMLINK
                        || d.dwReserved0 == Win32.IO_REPARSE_TAG_MOUNT_POINT);
                boolean folder = (a & Win32.FILE_ATTRIBUTE_DIRECTORY) != 0;
                boolean online = (a & (Win32.FILE_ATTRIBUTE_OFFLINE | Win32.FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS)) != 0;
                String ext = folder ? "" : ext(name);
                out.add(new FileItem(dir.resolve(name), name, folder, link, hidden,
                        (a & Win32.FILE_ATTRIBUTE_SYSTEM) != 0, online, folder ? 0 : d.fileSize(), d.modified(), d.created(), a,
                        ShellInfo.typeName(ext, folder), false, 0));
            } while (k.FindNextFileW(h, d));
        } finally {
            k.FindClose(h);
        }
        return out;
    }

    private static List<FileItem> loadNio(Path dir, boolean showHidden) throws IOException {
        List<FileItem> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                FileItem item = item(p);
                if (item != null && (showHidden || !item.isHidden())) out.add(item);
            }
        }
        return out;
    }

    /** One item, read on its own (after a rename, for Properties, for search results). */
    public static FileItem item(Path p) {
        try {
            DosFileAttributes a = Files.readAttributes(p, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean link = FileOps.isLink(p, a);
            boolean folder = a.isDirectory() || (a.isSymbolicLink() && Files.isDirectory(p));
            String name = FileOps.name(p);
            int attrs = (a.isReadOnly() ? 1 : 0) | (a.isHidden() ? 2 : 0) | (a.isSystem() ? 4 : 0)
                    | (folder ? 0x10 : 0) | (a.isArchive() ? 0x20 : 0);
            String ext = folder ? "" : ext(name);
            return new FileItem(p, name, folder, link, a.isHidden(), a.isSystem(), false, folder ? 0 : a.size(),
                    a.lastModifiedTime().toMillis(), a.creationTime().toMillis(), attrs, ShellInfo.typeName(ext, folder),
                    false, 0);
        } catch (IOException e) {
            return null;
        }
    }

    /** "This PC": the drives, with their labels, capacity and free space. */
    public static List<FileItem> drives() {
        List<FileItem> out = new ArrayList<>();
        for (Places.Volume v : Places.volumes()) {
            out.add(new FileItem(v.root(), v.title(), true, false, false, false, false, v.total(), 0, 0, 0x10,
                    v.kind(), true, v.free()));
        }
        return out;
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }
}
