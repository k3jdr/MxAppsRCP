package com.k3jdr.mx.core.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Copying a table's rows as tab-separated columns under the column titles, ready for a spreadsheet or a ticket
 * (MxAppsIOS/Shared/Copy/TableCopy.swift). Hidden (zero-width) columns are left out.
 */
public final class TableCopy {
    private TableCopy() {}

    /** The selected rows (all rows when {@code all}) as TSV with a header line. */
    public static String rows(Table table, boolean all) {
        int[] order = table.getColumnOrder();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int index : order) {
            TableColumn col = table.getColumn(index);
            if (col.getWidth() == 0) continue;
            if (!first) sb.append('\t');
            sb.append(clean(col.getText()));
            first = false;
        }
        sb.append("\r\n");
        TableItem[] items = all ? table.getItems() : table.getSelection();
        for (TableItem item : items) {
            first = true;
            for (int index : order) {
                if (table.getColumn(index).getWidth() == 0) continue;
                if (!first) sb.append('\t');
                sb.append(clean(item.getText(index)));
                first = false;
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    public static void copySelected(Table table) {
        if (table.getSelectionCount() == 0) return;
        Clipboards.copyText(table.getDisplay(), rows(table, false));
    }

    public static void copyAll(Table table) {
        Clipboards.copyText(table.getDisplay(), rows(table, true));
    }

    /** Adds Copy Row / Copy All Rows to a table's context menu (creating the menu if it has none). */
    public static void addMenuItems(Table table) {
        Menu menu = table.getMenu();
        if (menu == null) {
            menu = new Menu(table);
            table.setMenu(menu);
        } else if (menu.getItemCount() > 0) {
            new MenuItem(menu, SWT.SEPARATOR);
        }
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.addListener(SWT.Selection, e -> copySelected(table));
        MenuItem copyAll = new MenuItem(menu, SWT.PUSH);
        copyAll.setText("Copy All Rows");
        copyAll.addListener(SWT.Selection, e -> copyAll(table));
        menu.addListener(SWT.Show, e -> {
            int n = table.getSelectionCount();
            copy.setText(n > 1 ? "Copy " + n + " Rows" : "Copy Row");
            copy.setEnabled(n > 0);
        });
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
