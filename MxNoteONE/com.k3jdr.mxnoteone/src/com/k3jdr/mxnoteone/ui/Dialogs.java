package com.k3jdr.mxnoteone.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxnoteone.model.Kinds.NoteColor;
import com.k3jdr.mxnoteone.model.PageMeta;
import com.k3jdr.mxnoteone.model.SectionNode;
import com.k3jdr.mxnoteone.store.Notebook;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Refs.SectionRef;

/** The app's sheets as dialogs: rename, new notebook, insert table, link, move or copy a page, passwords. */
final class Dialogs {
    private Dialogs() {}

    /** A small dialog whose content and OK action are given. */
    private abstract static class Form extends Dialog {
        private final String title, ok;

        Form(Shell parent, String title, String ok) {
            super(parent);
            this.title = title;
            this.ok = ok;
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }

        @Override
        protected void configureShell(Shell s) {
            super.configureShell(s);
            s.setText(title);
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            Composite c = new Composite(area, SWT.NONE);
            c.setLayout(Ui.grid(2, 0, 6));
            c.setLayoutData(Ui.fill());
            build(c);
            return area;
        }

        abstract void build(Composite c);

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            extraButtons(parent);
            createButton(parent, IDialogConstants.OK_ID, ok, true);
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        }

        void extraButtons(Composite parent) {}

        static Text field(Composite c, String label, String value) {
            Ui.label(c, label);
            Text t = new Text(c, SWT.BORDER | SWT.SINGLE);
            t.setText(value == null ? "" : value);
            GridData gd = Ui.fillH();
            gd.widthHint = 320;
            t.setLayoutData(gd);
            return t;
        }
    }

    /** A name for something (rename a notebook, section, group). */
    static String ask(Shell shell, String title, String message, String initial) {
        InputDialog d = new InputDialog(shell, title, message, initial, v -> v == null || v.isBlank() ? "Type a name." : null);
        return d.open() == Dialog.OK ? d.getValue().strip() : null;
    }

    /** Insert ▸ Table…: rows and columns. */
    static int[] insertTable(Shell shell) {
        int[] out = { 3, 3 };
        Form f = new Form(shell, "Insert Table", "Insert") {
            Spinner rows, cols;

            @Override
            void build(Composite c) {
                Ui.label(c, "Rows:");
                rows = new Spinner(c, SWT.BORDER);
                rows.setValues(3, 1, 200, 0, 1, 5);
                Ui.label(c, "Columns:");
                cols = new Spinner(c, SWT.BORDER);
                cols.setValues(3, 1, 50, 0, 1, 5);
                QuickHelp.help(rows, "Rows", "How many rows the new table starts with (Return in the last row adds more).");
                QuickHelp.help(cols, "Columns", "How many columns the new table starts with.");
            }

            @Override
            protected void okPressed() {
                out[0] = rows.getSelection();
                out[1] = cols.getSelection();
                super.okPressed();
            }
        };
        return f.open() == Dialog.OK ? out : null;
    }

    /** New Notebook…: its name, colour and where it's kept. */
    static void newNotebook(Shell shell, NotebookStore store) {
        Form f = new Form(shell, "New Notebook", "Create") {
            Text name, folder;
            Combo color;

            @Override
            void build(Composite c) {
                name = field(c, "Name:", "New Notebook");
                Ui.label(c, "Color:");
                color = new Combo(c, SWT.READ_ONLY);
                for (NoteColor nc : NoteColor.values()) color.add(nc.title());
                color.select(NoteColor.BLUE.ordinal());
                Ui.label(c, "Location:");
                Composite row = new Composite(c, SWT.NONE);
                row.setLayout(Ui.grid(2, 0, 4));
                row.setLayoutData(Ui.fillH());
                folder = new Text(row, SWT.BORDER);
                folder.setText(store.library().toString());
                folder.setLayoutData(Ui.fillH());
                Ui.button(row, "Choose…", () -> {
                    DirectoryDialog dd = new DirectoryDialog(getShell());
                    dd.setFilterPath(folder.getText());
                    String p = dd.open();
                    if (p != null) folder.setText(p);
                });
                QuickHelp.help(name, "Name", "The notebook's name; its folder is Name.mxnotebook.");
                QuickHelp.help(folder, "Location", "The folder the notebook is kept in — on this PC, or a OneDrive or Dropbox folder to open it on a Mac too.");
            }

            @Override
            protected void okPressed() {
                if (name.getText().isBlank()) return;
                store.createNotebook(name.getText().strip(), NoteColor.values()[Math.max(0, color.getSelectionIndex())], Path.of(folder.getText()),
                        "New Section 1", com.k3jdr.mxnoteone.store.Templates.BLANK);
                super.okPressed();
            }
        };
        f.open();
    }

    /** Move or Copy Page…: a section in any open notebook. */
    static void moveOrCopy(Shell shell, NotebookStore store, PageRef ref) {
        PageMeta meta = store.meta(ref);
        if (meta == null) return;
        java.util.List<SectionRef> targets = new ArrayList<>();
        Form f = new Form(shell, "Move or Copy Page", "Move") {
            List list;

            @Override
            void build(Composite c) {
                Label l = Ui.label(c, "Move or copy “" + meta.displayTitle() + "” to:");
                l.setLayoutData(Ui.span(2));
                list = new List(c, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
                GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
                gd.heightHint = 260;
                gd.widthHint = 380;
                list.setLayoutData(gd);
                for (Notebook nb : store.notebooks) {
                    for (SectionNode n : SectionNode.sections(nb.manifest.sections)) {
                        SectionRef s = new SectionRef(nb.id(), n.id);
                        if (store.isLocked(s)) continue;
                        targets.add(s);
                        list.add(store.location(s));
                    }
                }
                QuickHelp.help(list, "Sections", "The sections of every open notebook (locked ones aside). Move takes the page there; Copy leaves it here too.");
            }

            @Override
            void extraButtons(Composite parent) {
                createButton(parent, IDialogConstants.CLIENT_ID + 1, "Copy", false);
            }

            @Override
            protected void buttonPressed(int id) {
                if (id == IDialogConstants.CLIENT_ID + 1 || id == IDialogConstants.OK_ID) {
                    int i = list.getSelectionIndex();
                    if (i < 0) return;
                    store.movePage(ref, targets.get(i), id != IDialogConstants.OK_ID, getShell().getDisplay());
                    setReturnCode(OK);
                    close();
                    return;
                }
                super.buttonPressed(id);
            }
        };
        f.open();
    }

    /** A password (and a hint when setting one); null when cancelled. */
    static String[] password(Shell shell, String title, String message, boolean setting) {
        String[] out = new String[2];
        Form f = new Form(shell, title, setting ? "Protect" : "Unlock") {
            Text pw, again, hint;

            @Override
            void build(Composite c) {
                Label m = Ui.label(c, message);
                m.setLayoutData(Ui.span(2));
                Ui.label(c, "Password:");
                pw = new Text(c, SWT.BORDER | SWT.PASSWORD);
                pw.setLayoutData(Ui.fillH());
                if (setting) {
                    Ui.label(c, "Again:");
                    again = new Text(c, SWT.BORDER | SWT.PASSWORD);
                    again.setLayoutData(Ui.fillH());
                    hint = field(c, "Hint:", "");
                    Label w = Ui.label(c, "A forgotten password cannot be recovered.");
                    w.setForeground(Ui.SECONDARY);
                    w.setLayoutData(Ui.span(2));
                }
            }

            @Override
            protected void okPressed() {
                if (pw.getText().isEmpty()) return;
                if (setting && !pw.getText().equals(again.getText())) {
                    com.k3jdr.mx.core.ui.ErrorDialog.show(getShell(), "Passwords don't match", "Type the same password twice.");
                    return;
                }
                out[0] = pw.getText();
                out[1] = setting ? hint.getText() : "";
                super.okPressed();
            }
        };
        return f.open() == Dialog.OK ? out : null;
    }

    /**
     * Insert ▸ Link… (Ctrl+K) and Edit Link: the words and the address — a web address, an email address, or a page
     * in the open notebooks. With the caret in a link, the whole link is edited.
     */
    static void link(PageView page, NoteText t) {
        if (t == null || t.isDisposed()) return;
        Point sel = t.getSelection();
        int[] existing = t.linkRange(sel.x);
        int start = existing != null ? existing[0] : sel.x, end = existing != null ? existing[1] : sel.y;
        String words = t.getText().substring(start, end).replace('\r', ' ');
        String oldUrl = existing != null ? t.styleAt(existing[0]).link() : null;
        NotebookStore store = page.store;
        java.util.List<PageRef> pages = new ArrayList<>();
        Form f = new Form(page.root.getShell(), existing != null ? "Edit Link" : "Insert Link", existing != null ? "Update" : "Insert") {
            Text text, address, filter;
            List list;

            @Override
            void build(Composite c) {
                text = field(c, "Text to display:", words);
                address = field(c, "Address:", oldUrl == null ? clipboardLink() : oldUrl);
                Ui.label(c, "Or a page:");
                filter = new Text(c, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
                filter.setMessage("Find a page");
                filter.setLayoutData(Ui.fillH());
                list = new List(c, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
                GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
                gd.heightHint = 180;
                list.setLayoutData(gd);
                filter.addListener(SWT.Modify, e -> fill());
                list.addListener(SWT.Selection, e -> {
                    int i = list.getSelectionIndex();
                    if (i < 0) return;
                    address.setText(NotebookStore.link(pages.get(i)));
                    if (text.getText().isBlank()) text.setText(store.meta(pages.get(i)).displayTitle());
                });
                fill();
                QuickHelp.help(text, "Text to display", "The words that show as the link. With words selected in the note, they are the link.");
                QuickHelp.help(address, "Address", "A web address (https://…, or www…), an email address, or a link copied with Copy Link to Page or Paragraph.");
                QuickHelp.help(list, "Pages", "Pick a page of your notebooks to link to it.");
            }

            void fill() {
                list.removeAll();
                pages.clear();
                String q = filter.getText().strip().toLowerCase(Locale.ROOT);
                for (Notebook nb : store.notebooks) {
                    for (SectionNode n : SectionNode.sections(nb.manifest.sections)) {
                        var sc = nb.sections.get(n.id);
                        if (sc == null) continue;
                        for (PageMeta m : sc.pages) {
                            if (!q.isEmpty() && !m.displayTitle().toLowerCase(Locale.ROOT).contains(q)) continue;
                            pages.add(new PageRef(nb.id(), n.id, m.id));
                            list.add(m.displayTitle() + "   — " + nb.name() + " › " + n.name);
                            if (pages.size() > 400) return;
                        }
                    }
                }
            }

            String clipboardLink() {
                String clip = com.k3jdr.mx.core.ui.Clipboards.text(page.root.getDisplay());
                if (clip != null && (clip.startsWith("http://") || clip.startsWith("https://") || clip.startsWith(NotebookStore.LINK_SCHEME + "://"))) {
                    return clip.strip();
                }
                return "";
            }

            @Override
            void extraButtons(Composite parent) {
                if (existing != null) createButton(parent, IDialogConstants.CLIENT_ID + 2, "Remove Link", false);
            }

            @Override
            protected void buttonPressed(int id) {
                if (id == IDialogConstants.CLIENT_ID + 2) {
                    t.setSelection(start, end);
                    t.removeLink();
                    close();
                    return;
                }
                super.buttonPressed(id);
            }

            @Override
            protected void okPressed() {
                String url = url(address.getText());
                if (url == null) return;
                String shown = text.getText();
                if (existing != null || end > start) {
                    if (!shown.isEmpty() && !shown.equals(words)) {
                        t.setSelection(start, end);
                        t.insertTyped(shown);
                        t.addLink(url, shown, start, start + shown.length());
                    } else {
                        t.addLink(url, shown, start, end);
                    }
                } else {
                    t.addLink(url, shown.isEmpty() ? url : shown, start, start);
                }
                super.okPressed();
            }
        };
        f.open();
        if (!t.isDisposed()) t.setFocus();
    }

    /** A link's address as typed: https:// is added to a web address, mailto: to an email address. */
    static String url(String typed) {
        String s = typed == null ? "" : typed.strip();
        if (s.isEmpty()) return null;
        if (s.contains("://") || s.startsWith("mailto:")) return s;
        if (s.contains("@") && !s.contains(" ")) return "mailto:" + s;
        return "https://" + s;
    }

    /** Find on Page: a small window that finds the next match as you press Return. */
    static void find(PageView page) {
        Shell shell = new Shell(page.root.getShell(), SWT.TOOL | SWT.TITLE | SWT.CLOSE);
        shell.setText("Find on Page");
        shell.setLayout(Ui.grid(3, 8, 6));
        Text q = new Text(shell, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        q.setText(page.lastFind());
        GridData gd = Ui.fillH();
        gd.widthHint = 220;
        q.setLayoutData(gd);
        Label status = new Label(shell, SWT.NONE);
        Consumer<Boolean> go = forward -> status.setText(page.find(q.getText(), forward) ? "" : "Not found");
        Button prev = Ui.button(shell, "▲", () -> go.accept(false));
        Button next = Ui.button(shell, "▼", () -> go.accept(true));
        prev.setToolTipText("Find Previous (Shift+F3)");
        next.setToolTipText("Find Next (F3)");
        status.setLayoutData(Ui.span(3));
        q.addListener(SWT.DefaultSelection, e -> go.accept(true));
        QuickHelp.help(q, "Find on Page", "Type words and press Return to select the next place they're found on this page.", "F3 / Shift+F3");
        shell.pack();
        Point at = page.root.toDisplay(page.root.getSize().x - shell.getSize().x - 24, 8);
        shell.setLocation(at);
        shell.open();
        q.setFocus();
        q.selectAll();
    }
}
