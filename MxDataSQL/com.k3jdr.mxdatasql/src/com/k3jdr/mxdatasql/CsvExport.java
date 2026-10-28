package com.k3jdr.mxdatasql;

import java.nio.file.Path;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * Export CSV… (CSVExport.swift): UTF-8, a header row, commas, CRLF (RFC 4180). When the table or query has more rows
 * than were fetched, the loaded rows or all rows (the grid's query run again, read-only, streamed to the file).
 */
public final class CsvExport {
    private CsvExport() {}

    public static void begin(Shell shell, Session session) {
        GridResult g = session.grid;
        if (g == null) return;
        boolean more = g.hasMore || (g.totalRows != null && g.totalRows > g.rows.size());
        int edits = session.edits.size();
        boolean allRows = false;
        if (more && !g.isWriteResult) {
            String loaded = "Loaded rows (" + Help.n(g.rows.size()) + ")"
                    + (edits > 0 ? ", including " + edits + " unapplied edit" + (edits == 1 ? "" : "s") : "");
            String all = "All rows" + (g.totalRows != null ? " (" + Help.n(g.totalRows) + ")" : "") + " — runs the query again, read-only";
            MessageDialog d = new MessageDialog(shell, "Export CSV", null, "Which rows should the CSV file have?\n\n• " + loaded + "\n• " + all,
                    MessageDialog.QUESTION, 0, "Loaded Rows", "All Rows", "Cancel");
            int choice = d.open();
            if (choice == 2 || choice < 0) return;
            allRows = choice == 1;
        } else if (edits > 0) {
            MessageDialog.openInformation(shell, "Export CSV", "Includes " + edits + " unapplied edit" + (edits == 1 ? "" : "s") + " shown in the grid.");
        }
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Export CSV");
        fd.setFilterExtensions(new String[] { "*.csv" });
        fd.setFilterNames(new String[] { "CSV files (*.csv)" });
        fd.setFileName((g.ref() != null ? g.ref().name() : "query-result") + ".csv");
        fd.setOverwrite(true);
        String file = fd.open();
        if (file == null) return;
        session.exportCSV(Path.of(file), allRows);
    }
}
