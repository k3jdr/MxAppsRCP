package com.k3jdr.mxfilemgr.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.SortOrder;

/**
 * Compares two folder trees for Synchronize Folders (SyncView.swift's SyncEngine): by date and size (two seconds
 * of tolerance), or by content; names match without regard to case, as on Windows.
 */
final class SyncEngine {
    private SyncEngine() {}

    enum Status {
        LEFT_ONLY("left only"), RIGHT_ONLY("right only"), LEFT_NEWER("left newer"), RIGHT_NEWER("right newer"),
        DIFFERS("different"), EQUAL("equal");

        final String title;

        Status(String title) { this.title = title; }
    }

    enum Action {
        TO_RIGHT("→"), TO_LEFT("←"), SKIP("–");

        final String symbol;

        Action(String symbol) { this.symbol = symbol; }

        Action next() { return values()[(ordinal() + 1) % values().length]; }
    }

    record Side(long size, long modified) {}

    static final class Entry {
        final String path;           // relative, with \ between folders
        final boolean isDirectory;
        final Side left, right;
        final Status status;
        Action action;

        Entry(String path, boolean isDirectory, Side left, Side right, Status status, Action action) {
            this.path = path;
            this.isDirectory = isDirectory;
            this.left = left;
            this.right = right;
            this.status = status;
            this.action = action;
        }
    }

    private record Item(String name, boolean isDir, long size, long modified) {}

    static List<Entry> compare(Path left, Path right, boolean recursive, boolean includeHidden, boolean byContent, AtomicBoolean cancel) {
        List<Entry> result = new ArrayList<>();
        walk(left, right, "", recursive, includeHidden, byContent, cancel, result);
        return result;
    }

    private static Map<String, Item> list(Path dir, boolean includeHidden) {
        Map<String, Item> map = new HashMap<>();
        if (!Files.isDirectory(dir)) return map;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                try {
                    DosFileAttributes a = Files.readAttributes(p, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!includeHidden && a.isHidden()) continue;
                    boolean dir2 = a.isDirectory() && !FileOps.isLink(p, a);
                    String name = FileOps.name(p);
                    map.put(name.toLowerCase(Locale.ROOT), new Item(name, dir2, dir2 ? 0 : a.size(), a.lastModifiedTime().toMillis()));
                } catch (IOException e) {
                    // an item that can't be read is left out
                }
            }
        } catch (IOException e) {
            // an unreadable folder counts as empty
        }
        return map;
    }

    private static void walk(Path left, Path right, String rel, boolean recursive, boolean includeHidden, boolean byContent,
            AtomicBoolean cancel, List<Entry> result) {
        if (cancel.get()) return;
        Map<String, Item> l = list(rel.isEmpty() ? left : left.resolve(rel), includeHidden);
        Map<String, Item> r = list(rel.isEmpty() ? right : right.resolve(rel), includeHidden);
        TreeSet<String> keys = new TreeSet<>((a, b) -> {
            int c = SortOrder.NATURAL.compare(a, b);
            return c != 0 ? c : a.compareTo(b);
        });
        keys.addAll(l.keySet());
        keys.addAll(r.keySet());
        for (String k : keys) {
            if (cancel.get()) return;
            Item a = l.get(k), b = r.get(k);
            String name = a != null ? a.name() : b.name();
            String path = rel.isEmpty() ? name : rel + "\\" + name;
            Side ls = a == null ? null : new Side(a.size(), a.modified());
            Side rs = b == null ? null : new Side(b.size(), b.modified());
            if (a != null && b != null) {
                if (a.isDir() && b.isDir()) {
                    if (recursive) walk(left, right, path, true, includeHidden, byContent, cancel, result);
                    continue;
                }
                if (a.isDir() != b.isDir()) {
                    result.add(new Entry(path, a.isDir(), ls, rs, Status.DIFFERS, Action.SKIP));
                    continue;
                }
                long dt = a.modified() - b.modified();
                Status status;
                if (a.size() == b.size() && Math.abs(dt) <= 2000) status = Status.EQUAL;
                else if (dt > 2000) status = Status.LEFT_NEWER;
                else if (dt < -2000) status = Status.RIGHT_NEWER;
                else status = Status.DIFFERS;
                if (byContent && a.size() == b.size()) {
                    boolean same = sameContents(left.resolve(path), right.resolve(path), cancel);
                    if (same) status = Status.EQUAL;
                    else if (status == Status.EQUAL) status = Status.DIFFERS;
                }
                Action action = status == Status.LEFT_NEWER ? Action.TO_RIGHT : status == Status.RIGHT_NEWER ? Action.TO_LEFT : Action.SKIP;
                result.add(new Entry(path, false, ls, rs, status, action));
            } else if (a != null) {
                result.add(new Entry(path, a.isDir(), ls, null, Status.LEFT_ONLY, Action.TO_RIGHT));
            } else {
                result.add(new Entry(path, b.isDir(), null, rs, Status.RIGHT_ONLY, Action.TO_LEFT));
            }
        }
    }

    static boolean sameContents(Path a, Path b, AtomicBoolean cancel) {
        try (InputStream ia = Files.newInputStream(a); InputStream ib = Files.newInputStream(b)) {
            byte[] ba = new byte[1 << 20], bb = new byte[1 << 20];
            while (true) {
                if (cancel != null && cancel.get()) return false;
                int na = ia.readNBytes(ba, 0, ba.length);
                int nb = ib.readNBytes(bb, 0, bb.length);
                if (na != nb) return false;
                if (na == 0) return true;
                if (!Arrays.equals(ba, 0, na, bb, 0, nb)) return false;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
