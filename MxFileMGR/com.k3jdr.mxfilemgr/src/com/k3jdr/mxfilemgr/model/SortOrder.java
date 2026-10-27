package com.k3jdr.mxfilemgr.model;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** How a tab sorts its items: a column and a direction (folders first unless Settings say otherwise). */
public record SortOrder(SortKey key, boolean ascending) {
    public static final SortOrder DEFAULT = new SortOrder(SortKey.NAME, true);

    /** Natural, case-insensitive name order ("file2" before "file10"), as Explorer and Finder sort. */
    public static final Comparator<String> NATURAL = new Comparator<>() {
        private final Collator collator = Collator.getInstance();

        @Override
        public int compare(String a, String b) {
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i), cb = b.charAt(j);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int si = i, sj = j;
                    while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                    while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                    String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                    String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                    if (na.length() != nb.length()) return na.length() - nb.length();
                    int c = na.compareTo(nb);
                    if (c != 0) return c;
                } else {
                    int si = i, sj = j;
                    while (i < a.length() && !Character.isDigit(a.charAt(i))) i++;
                    while (j < b.length() && !Character.isDigit(b.charAt(j))) j++;
                    int c;
                    synchronized (collator) {
                        c = collator.compare(a.substring(si, i).toLowerCase(), b.substring(sj, j).toLowerCase());
                    }
                    if (c != 0) return c;
                }
            }
            return (a.length() - i) - (b.length() - j);
        }
    };

    public SortOrder toggled(SortKey k) {
        return k == key ? new SortOrder(k, !ascending) : new SortOrder(k, true);
    }

    public List<FileItem> sort(List<FileItem> items, boolean foldersFirst) {
        List<FileItem> list = new ArrayList<>(items);
        Comparator<FileItem> byKey = switch (key) {
            case NAME -> (a, b) -> NATURAL.compare(a.name(), b.name());
            case EXT -> (a, b) -> NATURAL.compare(a.ext(), b.ext());
            case SIZE -> Comparator.comparingLong(FileItem::size);
            case MODIFIED -> Comparator.comparingLong(FileItem::modified);
            case KIND -> (a, b) -> NATURAL.compare(a.kind() == null ? "" : a.kind(), b.kind() == null ? "" : b.kind());
        };
        Comparator<FileItem> full = byKey.thenComparing((a, b) -> NATURAL.compare(a.name(), b.name()));
        Comparator<FileItem> directed = ascending ? full : full.reversed();
        if (foldersFirst) {
            Comparator<FileItem> folders = (a, b) -> Boolean.compare(!a.isDirectory(), !b.isDirectory());
            list.sort(folders.thenComparing(directed));
        } else {
            list.sort(directed);
        }
        return list;
    }
}
