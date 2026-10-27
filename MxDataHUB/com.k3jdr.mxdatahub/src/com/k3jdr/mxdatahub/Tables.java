package com.k3jdr.mxdatahub;

import java.util.List;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;

/**
 * The screens' tables: rows that select (Shift- or Ctrl-click for several, Ctrl+A for all), Edit ▸ Copy (Ctrl+C) and
 * the context menu's Copy Row put them on the clipboard as tab-separated columns under the titles (TableCopy).
 */
final class Tables {
    private Tables() {}

    /** A column: its title, width, and SWT.LEFT or SWT.RIGHT (numbers). */
    record Col(String title, int width, int align) {}

    static Col col(String title, int width) { return new Col(title, width, SWT.LEFT); }

    static Col num(String title, int width) { return new Col(title, width, SWT.RIGHT); }

    /** A table with these columns, Copy Row in its context menu (after {@code menuFirst} items) and Quick Help. */
    static Table create(Composite parent, boolean multi, QuickHelpItem help, Col... cols) {
        Table t = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | (multi ? SWT.MULTI : SWT.SINGLE));
        t.setHeaderVisible(true);
        t.setLinesVisible(true);
        t.setLayoutData(Ui.fill());
        for (Col c : cols) {
            TableColumn tc = new TableColumn(t, c.align());
            tc.setText(c.title());
            tc.setWidth(c.width());
            tc.setMoveable(true);
            tc.setResizable(true);
        }
        if (help != null) QuickHelp.help(t, help.title(), help.detail(), help.shortcut());
        return t;
    }

    /** Adds Copy Row / Copy All Rows to the table's context menu (call after adding the table's own items). */
    static void copyMenu(Table t) {
        TableCopy.addMenuItems(t);
    }

    /** Shows {@code rows}, reusing the table's items; the item's data is the row. */
    static <T> void fill(Table t, List<T> rows, Function<T, String[]> cells) {
        if (t.isDisposed()) return;
        t.setRedraw(false);
        try {
            int n = rows.size();
            if (t.getItemCount() > n) t.remove(n, t.getItemCount() - 1);
            for (int i = 0; i < n; i++) {
                TableItem item = i < t.getItemCount() ? t.getItem(i) : new TableItem(t, SWT.NONE);
                T row = rows.get(i);
                String[] texts = cells.apply(row);
                for (int k = 0; k < texts.length; k++) {
                    item.setText(k, texts[k] == null ? "" : texts[k]);
                    item.setForeground(k, null);
                    item.setFont(k, null);
                }
                item.setData(row);
            }
        } finally {
            t.setRedraw(true);
        }
    }

    static void colour(TableItem item, int column, Color color) {
        item.setForeground(column, color);
    }

    /** The rows selected, in the table's order. */
    @SuppressWarnings("unchecked")
    static <T> List<T> selected(Table t) {
        return java.util.Arrays.stream(t.getSelection()).map(i -> (T) i.getData()).toList();
    }

    /** The row under {@code item}, or null. */
    @SuppressWarnings("unchecked")
    static <T> T row(TableItem item) {
        return item == null ? null : (T) item.getData();
    }

    /** The index of the column at x in the table's coordinates (-1 for none). */
    static int columnAt(Table t, int x) {
        int[] order = t.getColumnOrder();
        int left = -t.getHorizontalBar().getSelection();
        for (int index : order) {
            int w = t.getColumn(index).getWidth();
            if (x >= left && x < left + w) return index;
            left += w;
        }
        return -1;
    }
}
