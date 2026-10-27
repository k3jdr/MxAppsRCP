package com.k3jdr.mxfilemgr.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.k3jdr.mxfilemgr.model.Fmt;

/**
 * Compare Files by Content (DiffView.swift's Differ): a line diff of two text files with Myers' O((N+M)D)
 * algorithm; binary or very large files are compared byte for byte instead.
 */
final class Differ {
    private Differ() {}

    static final long SIZE_LIMIT = 8L << 20;
    /** More differences than this and the changed middle is shown as one removed and one added block. */
    static final int MAX_EDITS = 2500;

    enum Kind { SAME, REMOVED, ADDED }

    /** One line of the diff; {@code left} and {@code right} are 1-based line numbers, 0 where the line isn't on that side. */
    record Line(Kind kind, int left, int right, String text) {}

    /** Either diff lines, or a message (identical, binary, too large, unreadable) — or both when identical. */
    record Result(List<Line> lines, String message) {}

    static Result diff(Path a, Path b) {
        long sa, sb;
        try {
            sa = Files.size(a);
            sb = Files.size(b);
        } catch (IOException e) {
            return new Result(List.of(), "One of the files can't be read.");
        }
        if (sa > SIZE_LIMIT || sb > SIZE_LIMIT) {
            boolean same = sa == sb && SyncEngine.sameContents(a, b, null);
            return new Result(List.of(), same ? "Large files are identical." : "Large files differ (" + Fmt.size(sa) + " vs " + Fmt.size(sb) + ").");
        }
        byte[] da, db;
        try {
            da = Files.readAllBytes(a);
            db = Files.readAllBytes(b);
        } catch (IOException e) {
            return new Result(List.of(), "One of the files can't be read.");
        }
        if (hasNul(da) || hasNul(db)) {
            return new Result(List.of(), Arrays.equals(da, db) ? "Binary files are identical."
                    : "Binary files differ (" + Fmt.size(da.length) + " vs " + Fmt.size(db.length) + ").");
        }
        String[] left = TextDecoding.decode(da).text().split("\r?\n", -1);
        String[] right = TextDecoding.decode(db).text().split("\r?\n", -1);
        List<Line> lines = diffLines(left, right);
        boolean changed = lines.stream().anyMatch(l -> l.kind() != Kind.SAME);
        return new Result(lines, changed ? null : "The files are identical.");
    }

    private static boolean hasNul(byte[] d) {
        int n = Math.min(d.length, 8192);
        for (int i = 0; i < n; i++) if (d[i] == 0) return true;
        return false;
    }

    static List<Line> diffLines(String[] a, String[] b) {
        Map<String, Integer> ids = new HashMap<>();
        int[] x = new int[a.length], y = new int[b.length];
        for (int i = 0; i < a.length; i++) x[i] = ids.computeIfAbsent(a[i], k -> ids.size());
        for (int i = 0; i < b.length; i++) y[i] = ids.computeIfAbsent(b[i], k -> ids.size());
        int n = a.length, m = b.length;
        int pre = 0;
        while (pre < n && pre < m && x[pre] == y[pre]) pre++;
        int suf = 0;
        while (suf < n - pre && suf < m - pre && x[n - 1 - suf] == y[m - 1 - suf]) suf++;
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < pre; i++) out.add(new Line(Kind.SAME, i + 1, i + 1, a[i]));
        int[] xa = Arrays.copyOfRange(x, pre, n - suf), yb = Arrays.copyOfRange(y, pre, m - suf);
        List<int[]> ops = myers(xa, yb, MAX_EDITS);
        if (ops == null) {
            ops = new ArrayList<>();
            for (int i = 0; i < xa.length; i++) ops.add(new int[] { 1, i, -1 });
            for (int j = 0; j < yb.length; j++) ops.add(new int[] { 2, -1, j });
        }
        for (int[] op : ops) {
            switch (op[0]) {
                case 0 -> out.add(new Line(Kind.SAME, pre + op[1] + 1, pre + op[2] + 1, a[pre + op[1]]));
                case 1 -> out.add(new Line(Kind.REMOVED, pre + op[1] + 1, 0, a[pre + op[1]]));
                default -> out.add(new Line(Kind.ADDED, 0, pre + op[2] + 1, b[pre + op[2]]));
            }
        }
        for (int i = 0; i < suf; i++) {
            int li = n - suf + i, ri = m - suf + i;
            out.add(new Line(Kind.SAME, li + 1, ri + 1, a[li]));
        }
        return out;
    }

    /** Myers' shortest edit script: {0,i,j} same, {1,i,-1} delete a[i], {2,-1,j} insert b[j]; null past {@code maxD} edits. */
    static List<int[]> myers(int[] a, int[] b, int maxD) {
        int n = a.length, m = b.length, max = n + m;
        List<int[]> ops = new ArrayList<>();
        if (max == 0) return ops;
        int off = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        for (int d = 0; d <= max; d++) {
            if (d > maxD) return null;
            int[] snap = new int[2 * d + 3];
            System.arraycopy(v, off - d - 1, snap, 0, 2 * d + 3);
            trace.add(snap);
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) x = v[off + k + 1];
                else x = v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && x >= 0 && y >= 0 && a[x] == b[y]) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) return backtrack(trace, n, m, d);
            }
        }
        return null;
    }

    private static List<int[]> backtrack(List<int[]> trace, int n, int m, int dEnd) {
        List<int[]> ops = new ArrayList<>();
        int x = n, y = m;
        for (int d = dEnd; d >= 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int prevK = (k == -d || (k != d && v[k - 1 + d + 1] < v[k + 1 + d + 1])) ? k + 1 : k - 1;
            int prevX = v[prevK + d + 1], prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                ops.add(new int[] { 0, x - 1, y - 1 });
                x--;
                y--;
            }
            if (d > 0) {
                if (x == prevX) ops.add(new int[] { 2, -1, y - 1 });
                else ops.add(new int[] { 1, x - 1, -1 });
            }
            x = prevX;
            y = prevY;
        }
        Collections.reverse(ops);
        return ops;
    }
}
