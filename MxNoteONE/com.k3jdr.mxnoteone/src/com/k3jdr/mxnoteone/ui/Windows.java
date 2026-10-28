package com.k3jdr.mxnoteone.ui;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxnoteone.model.Kinds.TagKind;
import com.k3jdr.mxnoteone.model.NotebookManifest.TrashedItem;
import com.k3jdr.mxnoteone.store.Notebook;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.NotebookStore.TagHit;

/** The Tag Summary and Recycle Bin windows (MxNoteONEApp.swift's extra windows). */
final class Windows {
    private Windows() {}

    private static Shell tags, trash;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    private static String when(java.time.Instant t) {
        return LocalDateTime.ofInstant(t, ZoneId.systemDefault()).format(WHEN);
    }

    private static Table table(Composite parent, String[] titles, int[] widths) {
        Table t = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        t.setHeaderVisible(true);
        t.setLinesVisible(true);
        t.setLayoutData(Ui.fill());
        for (int i = 0; i < titles.length; i++) {
            TableColumn c = new TableColumn(t, SWT.NONE);
            c.setText(titles[i]);
            c.setWidth(widths[i]);
        }
        return t;
    }

    /** View ▸ Tag Summary: every tagged paragraph in the open notebooks; double-click goes to its page. */
    static void tagSummary(Shell owner) {
        if (tags != null && !tags.isDisposed()) {
            tags.setActive();
            return;
        }
        NotebookStore store = NotebookStore.get();
        Shell s = new Shell(owner, SWT.SHELL_TRIM);
        tags = s;
        s.setText("Tag Summary");
        s.setLayout(Ui.grid(3, 8, 6));
        Combo kind = new Combo(s, SWT.READ_ONLY);
        kind.add("All Tags");
        for (TagKind k : TagKind.values()) kind.add(k.symbol + "  " + k.title);
        kind.select(0);
        Button open = new Button(s, SWT.CHECK);
        open.setText("Only unchecked To Do items");
        Ui.button(s, "Refresh", () -> {}).setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        Table t = table(s, new String[] { "Tag", "Text", "Page", "Location", "Updated" }, new int[] { 130, 300, 170, 220, 140 });
        ((GridData) t.getLayoutData()).horizontalSpan = 3;
        TableCopy.addMenuItems(t);
        List<TagHit> shown = new ArrayList<>();
        Runnable fill = () -> {
            t.removeAll();
            shown.clear();
            List<TagHit> hits = new ArrayList<>(store.allTags());
            hits.sort(Comparator.comparing((TagHit h) -> h.item().kind().ordinal()).thenComparing(h -> h.modified(), Comparator.reverseOrder()));
            for (TagHit h : hits) {
                if (kind.getSelectionIndex() > 0 && h.item().kind() != TagKind.values()[kind.getSelectionIndex() - 1]) continue;
                if (open.getSelection() && (h.item().kind() != TagKind.TODO || h.item().done())) continue;
                TableItem it = new TableItem(t, SWT.NONE);
                String sym = h.item().kind() == TagKind.TODO && h.item().done() ? TagKind.TODO_DONE : h.item().kind().symbol;
                it.setText(new String[] { sym + "  " + h.item().kind().title, h.item().text(), h.pageTitle(), h.location(), when(h.modified()) });
                shown.add(h);
            }
        };
        kind.addListener(SWT.Selection, e -> fill.run());
        open.addListener(SWT.Selection, e -> fill.run());
        ((Button) s.getChildren()[2]).addListener(SWT.Selection, e -> fill.run());
        t.addListener(SWT.DefaultSelection, e -> {
            int i = t.getSelectionIndex();
            if (i >= 0) store.open(shown.get(i).ref());
        });
        QuickHelp.help(t, "Tagged Notes", "Every tagged paragraph of the open notebooks (locked sections aside). Double-click one to go to its page; Ctrl+C copies the rows.");
        QuickHelp.help(kind, "Show", "Only one kind of tag, or all of them.");
        Consumer<NotebookStore.Change> listener = c -> {
            if (!t.isDisposed() && c == NotebookStore.Change.PAGES) fill.run();
        };
        store.addListener(listener);
        fill.run();
        s.setSize(1000, 520);
        s.open();
    }

    /** View ▸ Recycle Bin: deleted pages and sections of a notebook, kept 60 days; restore or delete them. */
    static void recycleBin(Shell owner) {
        if (trash != null && !trash.isDisposed()) {
            trash.setActive();
            return;
        }
        NotebookStore store = NotebookStore.get();
        Shell s = new Shell(owner, SWT.SHELL_TRIM);
        trash = s;
        s.setText("Recycle Bin");
        s.setLayout(Ui.grid(1, 8, 6));
        Combo which = new Combo(s, SWT.READ_ONLY);
        which.setLayoutData(Ui.fillH());
        List<Notebook> books = new ArrayList<>(store.notebooks);
        for (Notebook nb : books) which.add(nb.name());
        Notebook current = store.currentNotebook();
        which.select(Math.max(0, books.indexOf(current)));
        Table t = table(s, new String[] { "Name", "Kind", "From", "Deleted" }, new int[] { 260, 90, 200, 160 });
        Composite buttons = new Composite(s, SWT.NONE);
        buttons.setLayout(Ui.grid(3, 0, 6));
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        List<TrashedItem> items = new ArrayList<>();
        Runnable fill = () -> {
            t.removeAll();
            items.clear();
            int i = which.getSelectionIndex();
            if (i < 0 || i >= books.size()) return;
            for (TrashedItem it : books.get(i).manifest.trash) {
                TableItem row = new TableItem(t, SWT.NONE);
                row.setText(new String[] { it.name, it.section ? "Section" : "Page", it.section ? "" : it.fromSectionName, when(it.deleted) });
                items.add(it);
            }
        };
        Button restore = Ui.button(buttons, "Restore", () -> {
            int nbi = which.getSelectionIndex();
            for (int i : t.getSelectionIndices()) store.restore(items.get(i), books.get(nbi), s.getDisplay());
            fill.run();
        });
        Button delete = Ui.button(buttons, "Delete Permanently", () -> {
            int nbi = which.getSelectionIndex();
            if (t.getSelectionCount() == 0 || !MessageDialog.openConfirm(s, "Delete Permanently", "Delete the selected items for good? This can't be undone.")) return;
            for (int i : t.getSelectionIndices()) store.deletePermanently(items.get(i), books.get(nbi), true);
            fill.run();
        });
        Button empty = Ui.button(buttons, "Empty Recycle Bin", () -> {
            int nbi = which.getSelectionIndex();
            if (nbi < 0 || !MessageDialog.openConfirm(s, "Empty Recycle Bin", "Delete everything in the Recycle Bin of “" + books.get(nbi).name() + "” for good?")) return;
            store.emptyTrash(books.get(nbi));
            fill.run();
        });
        QuickHelp.help(t, "Deleted Pages and Sections", "Deleted pages and sections are kept for 60 days. Select some and Restore them (a page goes back to its section).");
        QuickHelp.help(restore, "Restore", "Puts the selected pages and sections back.");
        QuickHelp.help(delete, "Delete Permanently", "Deletes the selected items and their files for good.");
        QuickHelp.help(empty, "Empty Recycle Bin", "Deletes everything in this notebook's Recycle Bin for good.");
        which.addListener(SWT.Selection, e -> fill.run());
        fill.run();
        s.setSize(760, 420);
        s.open();
    }
}
