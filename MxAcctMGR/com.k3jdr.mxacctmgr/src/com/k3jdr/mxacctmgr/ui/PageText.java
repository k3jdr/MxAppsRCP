package com.k3jdr.mxacctmgr.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.TableCopy;

/**
 * Edit ▸ Copy Page Text and a section's Copy Section (Shared/Copy/PageText.swift): the page's words laid out as on
 * screen — things side by side tab-separated (a grid's columns, a row of tiles), blocks one after another, a table as
 * tab-separated rows under its titles — also what is scrolled out of sight.
 */
public final class PageText {
    private PageText() {}

    private static final String SECTION = "mx.pagetext.section";

    /** Marks a page's column (nothing to do: the frame copies the current page's root). */
    static void page(Composite column) {
        column.setData("mx.pagetext.page", Boolean.TRUE);
    }

    /** Gives a section (a titled box, a lesson) Copy Section in its context menu, away from its text. */
    public static void section(Composite section, String title) {
        section.setData(SECTION, title);
        Menu menu = new Menu(section);
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Copy Section");
        copy.addListener(SWT.Selection, e -> Clipboards.copyText(section.getDisplay(), of(section)));
        section.setMenu(menu);
        for (Control c : section.getChildren()) {
            if (c instanceof Label || c instanceof Composite && !(c instanceof Table) && c.getMenu() == null) c.setMenu(menu);
        }
    }

    /** The text of a control and everything in it. */
    public static String of(Control c) {
        StringBuilder sb = new StringBuilder();
        append(sb, c);
        return sb.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static void append(StringBuilder sb, Control c) {
        if (c.isDisposed() || !c.getVisible()) return;
        String own = leaf(c);
        if (own != null) {
            if (!own.isEmpty()) sb.append(own).append('\n');
            return;
        }
        if (c instanceof Table t) {
            sb.append(TableCopy.rows(t, true).replace("\r\n", "\n")).append('\n');
            return;
        }
        if (!(c instanceof Composite comp)) return;
        Control[] kids = comp.getChildren();
        int columns = comp.getLayout() instanceof GridLayout g ? g.numColumns : 1;
        boolean section = comp.getData(SECTION) != null;
        if (section) sb.append('\n');
        if (columns > 1 && kids.length > 1 && kids.length <= 64 && allLeaves(kids)) {
            // A row (or a grid) of words side by side: tab-separated, a line per grid row.
            int col = 0;
            StringBuilder line = new StringBuilder();
            for (Control k : kids) {
                if (k.isDisposed() || !k.getVisible()) continue;
                String t = leaf(k);
                if (col > 0) line.append('\t');
                line.append(t == null ? "" : t.replace('\n', ' '));
                col++;
                if (col == columns) {
                    sb.append(line.toString().stripTrailing()).append('\n');
                    line.setLength(0);
                    col = 0;
                }
            }
            if (line.length() > 0) sb.append(line.toString().stripTrailing()).append('\n');
        } else if (columns > 1 && kids.length > 1 && allTiles(kids)) {
            // Tiles side by side: each tile's words on one line, the tiles tab-separated.
            StringBuilder line = new StringBuilder();
            for (Control k : kids) {
                if (k.isDisposed() || !k.getVisible()) continue;
                if (line.length() > 0) line.append('\t');
                line.append(of(k).replace('\n', ' '));
            }
            sb.append(line).append('\n');
        } else {
            for (Control k : kids) append(sb, k);
        }
        if (section) sb.append('\n');
    }

    private static boolean allLeaves(Control[] kids) {
        for (Control k : kids) if (leaf(k) == null) return false;
        return true;
    }

    private static boolean allTiles(Control[] kids) {
        for (Control k : kids) {
            if (!(k instanceof Composite comp) || k instanceof Table) return false;
            for (Control g : comp.getChildren()) if (leaf(g) == null) return false;
        }
        return true;
    }

    /** The words of a control that has its own (a label, a text, a button), or null for a container. */
    private static String leaf(Control c) {
        if (c.getData("mx.pagetext.text") instanceof String s) return s.strip();
        if (c instanceof Label l) return (l.getStyle() & SWT.SEPARATOR) != 0 ? "" : l.getText().replace("&&", "&");
        if (c instanceof Text t) return t.getText();
        if (c instanceof Button b) {
            if ((b.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0) return (b.getSelection() ? "☑ " : "☐ ") + b.getText().replace("&&", "&");
            return b.getText().replace("&&", "&");
        }
        if (c instanceof Combo cb) return cb.getText();
        if (c instanceof Spinner s) return s.getText();
        if (c instanceof Composite) return null;
        return "";
    }
}
