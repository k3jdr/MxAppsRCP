package com.k3jdr.mxnoteone.ui;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.function.UnaryOperator;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mxnoteone.model.Kinds.NoteColor;
import com.k3jdr.mxnoteone.model.Kinds.TagKind;
import com.k3jdr.mxnoteone.model.SectionNode;
import com.k3jdr.mxnoteone.store.Notebook;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Refs.SectionRef;
import com.k3jdr.mxnoteone.store.Templates;
import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.ParaStyle;
import com.k3jdr.mxnoteone.text.Paragraphs;

/**
 * What the menus, the ribbon, the sidebar and the page list do (MainCommands.swift, TextFormatter.swift): one place
 * for every command, acting on the text being typed in, the selected table cells, the page or the selection.
 */
public final class Commands {
    final Shell shell;
    final NotebookStore store = NotebookStore.get();
    final PageView page;

    Commands(Shell shell, PageView page) {
        this.shell = shell;
        this.page = page;
    }

    // MARK: Formatting

    /** Changes the formatting of the selected text, of what's typed next, or of the selected table cells. */
    public void chars(String name, UnaryOperator<CharStyle> f) {
        TableBox cells = page.cellTarget();
        if (cells != null) {
            cells.changeCells(name, f);
            return;
        }
        NoteText t = page.activeText();
        if (t == null) {
            shell.getDisplay().beep();
            return;
        }
        t.changeChars(name, f);
        t.setFocus();
    }

    /** The formatting of the selection, for the ribbon. */
    public List<CharStyle> currentStyles() {
        TableBox cells = page.cellTarget();
        if (cells != null) return cells.cellStyles();
        NoteText t = page.activeText();
        return t == null ? List.of() : t.selectionStyles();
    }

    private NoteText text() {
        NoteText t = page.activeText();
        if (t == null) shell.getDisplay().beep();
        else t.setFocus();
        return t;
    }

    public void toggleBold() {
        boolean all = currentStyles().stream().allMatch(CharStyle::bold);
        chars("Bold", s -> s.withBold(!all));
    }

    public void toggleItalic() {
        boolean all = currentStyles().stream().allMatch(CharStyle::italic);
        chars("Italic", s -> s.withItalic(!all));
    }

    public void toggleUnderline() {
        boolean all = currentStyles().stream().allMatch(CharStyle::underline);
        chars("Underline", s -> s.withUnderline(!all));
    }

    public void toggleStrike() {
        boolean all = currentStyles().stream().allMatch(CharStyle::strike);
        chars("Strikethrough", s -> s.withStrike(!all));
    }

    /** +1 superscript, -1 subscript (again takes it off). */
    public void toggleScript(int direction) {
        boolean on = currentStyles().stream().allMatch(s -> s.script() == direction);
        chars(direction > 0 ? "Superscript" : "Subscript", s -> s.withScript(on ? 0 : direction));
    }

    public void setFont(String family) { chars("Font", s -> s.withFont(family)); }

    public void setSize(double size) { chars("Font Size", s -> s.withSize(Math.max(1, Math.min(400, size)))); }

    public void stepSize(double delta) { chars("Font Size", s -> s.withSize(Math.max(6, Math.min(144, s.size() + delta)))); }

    public void setColor(RGB c) { chars("Font Color", s -> s.withColor(c)); }

    public void setHighlight(RGB c) { chars("Highlight", s -> s.withBackground(c)); }

    public void clearFormatting() {
        TableBox cells = page.cellTarget();
        if (cells != null) {
            cells.changeCells("Clear Formatting", s -> CharStyle.DEFAULT.withBold(s.bold() && s.size() == CharStyle.DEFAULT_SIZE).withLink(s.link()));
            return;
        }
        NoteText t = text();
        if (t != null) t.clearFormatting();
    }

    public void align(int a) {
        TableBox table = page.activeTable();
        if (table != null && (page.cellTarget() != null || table.editor() != null)) {
            table.alignCells(a);
            return;
        }
        NoteText t = text();
        if (t != null) t.changeParagraphs("Alignment", p -> p.withAlign(a));
    }

    public void bullets() {
        NoteText t = text();
        if (t != null) t.toggleBullets();
    }

    public void numbering() {
        NoteText t = text();
        if (t != null) t.toggleNumbering();
    }

    public void indent(int direction) {
        NoteText t = text();
        if (t != null) t.indent(direction);
    }

    public void style(Paragraphs.Style s) {
        TableBox cells = page.cellTarget();
        if (cells != null) {
            cells.changeCells(s.title, x -> s.apply(x));
            return;
        }
        NoteText t = text();
        if (t != null) t.applyStyle(s);
    }

    public void tag(TagKind k) {
        NoteText t = text();
        if (t != null) t.toggleTag(k);
    }

    public void removeTags() {
        NoteText t = text();
        if (t != null) t.removeTags();
    }

    // MARK: Inserting

    public void insertTable() {
        if (page.ref() == null) return;
        int[] size = Dialogs.insertTable(shell);
        if (size != null) page.insertTable(size[0], size[1]);
    }

    public void insertLink() {
        NoteText t = page.activeText();
        if (t == null) {
            shell.getDisplay().beep();
            return;
        }
        Dialogs.link(page, t);
    }

    public void insertDate() { page.insertString(LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))); }

    public void insertTime() { page.insertString(LocalDateTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))); }

    public void insertDateTime() {
        LocalDateTime now = LocalDateTime.now();
        page.insertString(now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) + " " + now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)));
    }

    // MARK: Pages

    public void newPage(boolean subpage) { store.addPageToCurrentSection(Templates.BLANK, subpage); }

    public void newPageFromTemplate(Templates t) { store.addPageToCurrentSection(t, false); }

    public void deletePage(PageRef ref) {
        if (ref == null) return;
        store.deletePage(ref);
    }

    public void moveOrCopy(PageRef ref) {
        if (ref != null) Dialogs.moveOrCopy(shell, store, ref);
    }

    public void copyLink(PageRef ref) {
        if (ref != null) Clipboards.copyText(shell.getDisplay(), NotebookStore.link(ref));
    }

    // MARK: Export and print

    /** Runs an export on the page, opening it first when it isn't the one shown. */
    private void onPage(PageRef ref, Runnable r) {
        if (ref == null) ref = page.ref();
        if (ref == null) {
            shell.getDisplay().beep();
            return;
        }
        page.flush();
        if (!ref.equals(page.ref())) store.open(ref);
        page.whenLoaded(ref, r);
    }

    public void exportPdf(PageRef ref) { onPage(ref, () -> Exporter.pdf(shell, page)); }

    public void print(PageRef ref) { onPage(ref, () -> Exporter.print(shell, page)); }

    public void exportMarkdown(PageRef ref) { onPage(ref, () -> Exporter.markdown(shell, page.ref(), page.currentContent())); }

    public void exportHtml(PageRef ref) { onPage(ref, () -> Exporter.html(shell, page.ref(), page.currentContent())); }

    // MARK: Sections and notebooks

    public void renameNode(SectionRef ref) {
        SectionNode n = store.node(ref);
        if (n == null) return;
        String name = Dialogs.ask(shell, n.group ? "Rename Section Group" : "Rename Section", "Name:", n.name);
        if (name != null) store.renameNode(ref, name);
    }

    public void newSectionGroup(String notebookId, String groupId) {
        SectionRef ref = store.addSectionGroup(notebookId, groupId, null);
        if (ref == null) return;
        String name = Dialogs.ask(shell, "New Section Group", "Name:", "New Section Group");
        if (name != null) store.renameNode(ref, name);
    }

    public void deleteNode(SectionRef ref) {
        SectionNode n = store.node(ref);
        if (n == null) return;
        if (!MessageDialog.openConfirm(shell, n.group ? "Delete Section Group" : "Delete Section",
                "Delete “" + n.name + "”? It goes to the notebook's Recycle Bin, where it's kept for 60 days.")) return;
        store.deleteNode(ref);
    }

    public void renameNotebook(String id) {
        Notebook nb = store.notebook(id);
        if (nb == null) return;
        String name = Dialogs.ask(shell, "Rename Notebook", "Name:", nb.name());
        if (name != null) store.renameNotebook(id, name);
    }

    public void openNotebook() {
        DirectoryDialog dd = new DirectoryDialog(shell);
        dd.setText("Open Notebook");
        dd.setMessage("Choose a notebook folder (Name.mxnotebook).");
        dd.setFilterPath(store.library().toString());
        String p = dd.open();
        if (p != null) store.openNotebook(Path.of(p), shell.getDisplay());
    }

    public void saveNotebookAs(String id) {
        Notebook nb = store.notebook(id);
        if (nb == null) return;
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Save Notebook As");
        fd.setFileName(nb.folder.getFileName().toString());
        fd.setFilterPath(store.library().toString());
        String p = fd.open();
        if (p != null) store.saveNotebookAs(id, Path.of(p), shell.getDisplay());
    }

    public void protect(SectionRef ref) {
        SectionNode n = store.node(ref);
        if (n == null || store.isLocked(ref)) return;
        String[] pw = Dialogs.password(shell, n.isProtected() ? "Change Password" : "Password Protect Section",
                "Pages, the page list and files of “" + n.name + "” are encrypted with the password (AES-GCM).", true);
        if (pw != null) store.setPassword(ref, pw[0], pw[1], shell.getDisplay(), () -> {});
    }

    public void unlock(SectionRef ref) {
        SectionNode n = store.node(ref);
        if (n == null || !store.isLocked(ref)) return;
        String hint = n.hint();
        String[] pw = Dialogs.password(shell, "Unlock Section", "“" + n.name + "” is password protected."
                + (hint.isBlank() ? "" : "\nHint: " + hint), false);
        if (pw == null) return;
        store.unlock(ref, pw[0], shell.getDisplay(), ok -> {
            if (!ok) MessageDialog.openError(shell, "Wrong password", "That password doesn't open “" + n.name + "”.");
        });
    }

    public void removePassword(SectionRef ref) {
        SectionNode n = store.node(ref);
        if (n == null || store.isLocked(ref)) return;
        if (MessageDialog.openConfirm(shell, "Remove Password", "Remove the password of “" + n.name + "”? Its files are stored unencrypted again.")) {
            store.removePassword(ref, shell.getDisplay());
        }
    }

    public void colorNode(SectionRef ref, NoteColor c) { store.setColor(ref, c); }

    // MARK: Edit menu target

    /** Cut, Copy, Paste and Select All when the focus isn't in a text field: table cells or containers. */
    EditActions.Target editTarget() {
        return new EditActions.Target() {
            @Override
            public boolean canCut() { return canCopy(); }

            @Override
            public void cut() {
                TableBox t = page.activeTable();
                if (t != null && t.canCopyCells()) t.cutCells();
                else if (page.selected() != null) page.cut(page.selected());
            }

            @Override
            public boolean canCopy() {
                TableBox t = page.activeTable();
                return t != null && t.canCopyCells() || page.selected() != null;
            }

            @Override
            public void copy() {
                TableBox t = page.activeTable();
                if (t != null && t.canCopyCells()) t.copyCells();
                else if (page.selected() != null) page.copy(page.selected());
            }

            @Override
            public boolean canPaste() { return page.hasPage(); }

            @Override
            public void paste() {
                TableBox t = page.activeTable();
                if (t != null && t.canCopyCells()) t.pasteCells();
                else page.paste(null);
            }

            @Override
            public boolean canSelectAll() { return page.activeTable() != null; }

            @Override
            public void selectAll() {
                TableBox t = page.activeTable();
                if (t != null) t.selectAll();
            }
        };
    }
}
