package com.k3jdr.mxnoteone.ui;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxnoteone.model.PageMeta;
import com.k3jdr.mxnoteone.model.SectionNode;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.NotebookStore.SearchHit;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Refs.SectionRef;
import com.k3jdr.mxnoteone.store.Templates;

/**
 * The page list (PageListView.swift): the selected section's pages with their date and first line; subpages
 * indented (two levels) and collapsible; drag to reorder (subpages move along; dropped next to a subpage a page
 * becomes one); right-click for the page's commands, or on empty space for Add New. While the search field has
 * text it shows the search results instead.
 */
final class PageListView {
    final Composite root;
    private final Composite stack;
    private final StackLayout stackLayout = new StackLayout();
    private final Label header;
    final Table table;
    private final Composite locked;
    private final Label lockedLabel;
    private final NotebookStore store = NotebookStore.get();
    private final Commands commands;
    private final List<Object> rows = new ArrayList<>();
    private String query = "";
    private boolean refreshing;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);

    PageListView(Composite parent, Commands commands) {
        this.commands = commands;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(Ui.grid(1, 0, 0));
        Composite top = new Composite(root, SWT.NONE);
        top.setLayout(Ui.grid(2, 6, 4));
        top.setLayoutData(Ui.fillH());
        header = new Label(top, SWT.NONE);
        header.setFont(Gfx.uiFont(1, SWT.BOLD));
        header.setLayoutData(Ui.fillH());
        ToolBar bar = new ToolBar(top, SWT.FLAT | SWT.RIGHT);
        ToolItem add = new ToolItem(bar, SWT.PUSH);
        add.setText("+ Page");
        add.setToolTipText("Add Page (Ctrl+N)");
        add.addListener(SWT.Selection, e -> commands.newPage(false));
        QuickHelp.help(bar, "Add Page", "A new page at the end of the section (after the selected page and its subpages).", "Ctrl+N");

        stack = new Composite(root, SWT.NONE);
        stack.setLayout(stackLayout);
        stack.setLayoutData(Ui.fill());
        table = new Table(stack, SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(false);
        table.setLinesVisible(false);
        table.addListener(SWT.MeasureItem, e -> e.height = Math.max(e.height, rowHeight()));
        table.addListener(SWT.EraseItem, e -> e.detail &= ~SWT.FOREGROUND);
        table.addListener(SWT.PaintItem, this::paintItem);
        table.addListener(SWT.Resize, e -> {
            if (table.getColumnCount() > 0) table.getColumn(0).setWidth(table.getClientArea().width);
        });
        new org.eclipse.swt.widgets.TableColumn(table, SWT.NONE).setWidth(260);
        table.addListener(SWT.Selection, e -> selected());
        table.addListener(SWT.MouseDown, this::mouseDown);
        Menu menu = new Menu(table);
        menu.addListener(SWT.Show, e -> fillMenu(menu));
        table.setMenu(menu);
        QuickHelp.provider(table, (c, p) -> describe(p));
        dragAndDrop();

        locked = new Composite(stack, SWT.NONE);
        locked.setLayout(Ui.grid(1, 16, 10));
        lockedLabel = Ui.wrap(locked, "");
        Ui.button(locked, "Unlock…", () -> {
            SectionRef s = store.selectedSection();
            if (s != null) commands.unlock(s);
        });
        stackLayout.topControl = table;

        store.addListener(c -> refresh());
    }

    private int rowHeight() {
        GC gc = new GC(table);
        try {
            gc.setFont(Gfx.uiFont(0, SWT.NORMAL));
            int a = gc.getFontMetrics().getHeight();
            gc.setFont(Gfx.uiFont(-1, SWT.NORMAL));
            return a + gc.getFontMetrics().getHeight() + 10;
        } finally {
            gc.dispose();
        }
    }

    /** Shows search results for the text (empty: the section's pages again). */
    void search(String text) {
        query = text == null ? "" : text.strip();
        refresh();
    }

    void refresh() {
        if (table.isDisposed() || refreshing) return;
        refreshing = true;
        try {
            rows.clear();
            SectionRef section = store.selectedSection();
            if (!query.isEmpty()) {
                List<SearchHit> hits = store.search(query);
                header.setText(hits.size() + (hits.size() == 1 ? " Result" : " Results"));
                rows.addAll(hits);
                stackLayout.topControl = table;
            } else if (section != null && store.isLocked(section)) {
                SectionNode n = store.node(section);
                header.setText(n == null ? "" : n.name);
                lockedLabel.setText("“" + (n == null ? "" : n.name) + "” is password protected."
                        + (n == null || n.hint().isBlank() ? "" : "\nHint: " + n.hint()));
                stackLayout.topControl = locked;
            } else {
                SectionNode n = section == null ? null : store.node(section);
                header.setText(n == null ? (store.selectedGroup() != null ? "Section Group" : "") : n.name);
                if (section != null) rows.addAll(store.visiblePages(section));
                stackLayout.topControl = table;
            }
            header.getParent().layout();
            stack.layout();
            table.setRedraw(false);
            table.setItemCount(rows.size());
            table.clearAll();
            int select = -1;
            for (int i = 0; i < rows.size(); i++) {
                TableItem item = table.getItem(i);
                Object row = rows.get(i);
                item.setData(row);
                item.setText(row instanceof PageMeta m ? m.displayTitle() : ((SearchHit) row).title());
                if (row instanceof PageMeta m && m.id.equals(store.selectedPageId())) select = i;
                if (row instanceof SearchHit h && h.ref().equals(store.currentPageRef())) select = i;
            }
            if (select >= 0) {
                if (table.getSelectionCount() != 1 || table.getSelectionIndex() != select) table.setSelection(select);
            } else {
                table.deselectAll();
            }
            table.setRedraw(true);
        } finally {
            refreshing = false;
        }
    }

    private void paintItem(Event e) {
        TableItem item = (TableItem) e.item;
        Object row = item.getData();
        GC gc = e.gc;
        Rectangle b = item.getBounds(0);
        boolean sel = (e.detail & SWT.SELECTED) != 0;
        int x = b.x + 6;
        String title, sub;
        boolean bold = true, placeholder = false;
        if (row instanceof PageMeta m) {
            x += m.level * 16;
            SectionRef s = store.selectedSection();
            boolean subs = s != null && store.hasSubpages(s, m.id);
            if (subs) {
                gc.setForeground(Gfx.color(Gfx.GRAY_TEXT));
                gc.setFont(Gfx.uiFont(-2, SWT.BOLD));
                gc.drawText(m.collapsed ? "▸" : "▾", x, b.y + 4, true);
            }
            x += 14;
            title = m.displayTitle();
            placeholder = m.title.isBlank();
            bold = m.level == 0;
            String date = LocalDate.ofInstant(m.modified, ZoneId.systemDefault()).format(DATE);
            String first = m.plainText.isEmpty() ? "" : m.plainText.split("\n", 2)[0];
            sub = first.isEmpty() ? date : date + "  " + first;
            if (m.hasOpenTodo()) {
                gc.setFont(Gfx.uiFont(0, SWT.NORMAL));
                gc.setForeground(Gfx.color(Gfx.GRAY_TEXT));
                gc.drawText("☐", b.x + b.width - 18, b.y + 4, true);
            }
        } else {
            SearchHit h = (SearchHit) row;
            title = h.title();
            sub = h.location() + "  —  " + h.snippet();
        }
        gc.setFont(Gfx.uiFont(0, bold ? SWT.BOLD : SWT.NORMAL));
        gc.setForeground(sel ? e.display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT)
                : placeholder ? Gfx.color(Gfx.GRAY_TEXT) : e.display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        int h1 = gc.getFontMetrics().getHeight();
        gc.drawText(clip(gc, title, b.x + b.width - x - 22), x, b.y + 4, true);
        gc.setFont(Gfx.uiFont(-1, SWT.NORMAL));
        if (!sel) gc.setForeground(Gfx.color(Gfx.GRAY_TEXT));
        gc.drawText(clip(gc, sub, b.x + b.width - x - 6), x, b.y + 5 + h1, true);
    }

    private static String clip(GC gc, String s, int width) {
        if (width <= 10 || gc.textExtent(s).x <= width) return s;
        String t = s;
        while (t.length() > 1 && gc.textExtent(t + "…").x > width) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private Object rowAt(Point p) {
        TableItem item = table.getItem(p);
        return item == null ? null : item.getData();
    }

    private void selected() {
        if (refreshing) return;
        TableItem[] sel = table.getSelection();
        if (sel.length != 1) return;
        Object row = sel[0].getData();
        if (row instanceof PageMeta m) store.selectPage(m.id);
        else if (row instanceof SearchHit h) store.open(h.ref());
    }

    private void mouseDown(Event e) {
        Object row = rowAt(new Point(e.x, e.y));
        if (!(row instanceof PageMeta m) || e.button != 1) return;
        SectionRef s = store.selectedSection();
        if (s == null || !store.hasSubpages(s, m.id)) return;
        int chevronX = 6 + m.level * 16;
        if (e.x >= chevronX - 2 && e.x <= chevronX + 14) store.toggleCollapsed(new PageRef(s.notebookId(), s.sectionId(), m.id));
    }

    private QuickHelpItem describe(Point p) {
        Object row = rowAt(p);
        if (row instanceof PageMeta m) {
            return new QuickHelpItem(m.displayTitle(), (m.level > 0 ? "A subpage. " : "A page. ")
                    + "Click to open it; drag to reorder it (its subpages move with it), or onto a section in the sidebar to move it there. Right-click for New Page / Subpage, Make Subpage, Promote, Move or Copy, Duplicate, Copy Link and Delete.");
        }
        if (row instanceof SearchHit h) return new QuickHelpItem(h.title(), "Found in " + h.location() + ". Click to open the page.");
        return new QuickHelpItem("Pages", "The section's pages. Right-click empty space for New Page, New Subpage, New Page from Template, New Section and New Section Group.");
    }

    // MARK: Menus

    private void fillMenu(Menu menu) {
        for (MenuItem mi : menu.getItems()) mi.dispose();
        Object row = rowAt(table.toControl(table.getDisplay().getCursorLocation()));
        SectionRef section = store.selectedSection();
        if (row instanceof SearchHit h) {
            ElementView.item(menu, "Open", () -> store.open(h.ref()));
            ElementView.item(menu, "Copy Link to this Page", () -> commands.copyLink(h.ref()));
            return;
        }
        if (!(row instanceof PageMeta m) || section == null) {
            if (section == null || store.isLocked(section)) return;
            ElementView.item(menu, "New Page", () -> commands.newPage(false));
            ElementView.item(menu, "New Subpage", () -> commands.newPage(true)).setEnabled(!store.pages(section).isEmpty());
            templatesMenu(menu);
            new MenuItem(menu, SWT.SEPARATOR);
            ElementView.item(menu, "New Section", () -> store.addSection(section.notebookId(), null, null, Templates.BLANK));
            ElementView.item(menu, "New Section Group", () -> commands.newSectionGroup(section.notebookId(), null));
            return;
        }
        PageRef ref = new PageRef(section.notebookId(), section.sectionId(), m.id);
        ElementView.item(menu, "New Page", () -> store.addPage(section, m.id, false, Templates.BLANK, null, true));
        ElementView.item(menu, "New Subpage", () -> store.addPage(section, m.id, true, Templates.BLANK, null, true));
        new MenuItem(menu, SWT.SEPARATOR);
        ElementView.item(menu, "Make Subpage", () -> store.changeLevel(ref, 1));
        ElementView.item(menu, "Promote Subpage", () -> store.changeLevel(ref, -1)).setEnabled(m.level > 0);
        if (store.hasSubpages(section, m.id)) {
            ElementView.item(menu, m.collapsed ? "Expand Subpages" : "Collapse Subpages", () -> store.toggleCollapsed(ref));
        }
        new MenuItem(menu, SWT.SEPARATOR);
        ElementView.item(menu, "Move or Copy…", () -> commands.moveOrCopy(ref));
        ElementView.item(menu, "Duplicate Page", () -> store.duplicatePage(ref, table.getDisplay()));
        ElementView.item(menu, "Copy Link to this Page", () -> commands.copyLink(ref));
        new MenuItem(menu, SWT.SEPARATOR);
        ElementView.item(menu, "Export as PDF…", () -> commands.exportPdf(ref));
        ElementView.item(menu, "Export as Markdown…", () -> commands.exportMarkdown(ref));
        ElementView.item(menu, "Export as HTML…", () -> commands.exportHtml(ref));
        ElementView.item(menu, "Print…", () -> commands.print(ref));
        new MenuItem(menu, SWT.SEPARATOR);
        ElementView.item(menu, "Delete Page", () -> commands.deletePage(ref));
    }

    private void templatesMenu(Menu menu) {
        MenuItem t = new MenuItem(menu, SWT.CASCADE);
        t.setText("New Page from Template");
        Menu sub = new Menu(t);
        t.setMenu(sub);
        for (Templates tpl : Templates.choices()) ElementView.item(sub, tpl.title, () -> commands.newPageFromTemplate(tpl));
    }

    // MARK: Drag and drop

    private void dragAndDrop() {
        DragSource source = new DragSource(table, DND.DROP_MOVE);
        source.setTransfer(LocalTransfer.get());
        source.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent e) {
                SectionRef s = store.selectedSection();
                Object row = rowAt(new Point(e.x, e.y));
                e.doit = s != null && query.isEmpty() && row instanceof PageMeta;
                if (e.doit) LocalTransfer.payload = new PageRef(s.notebookId(), s.sectionId(), ((PageMeta) row).id);
            }

            @Override
            public void dragSetData(DragSourceEvent e) {
                e.data = LocalTransfer.payload;
            }
        });
        DropTarget target = new DropTarget(table, DND.DROP_MOVE);
        target.setTransfer(new Transfer[] { LocalTransfer.get() });
        target.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetEvent e) {
                e.feedback = DND.FEEDBACK_SCROLL;
                SectionRef s = store.selectedSection();
                if (!(LocalTransfer.payload instanceof PageRef p) || s == null || !p.section().equals(s) || !query.isEmpty()) {
                    e.detail = DND.DROP_NONE;
                    return;
                }
                e.detail = DND.DROP_MOVE;
                TableItem item = (TableItem) e.item;
                if (item != null) {
                    Rectangle b = item.getBounds();
                    Point pt = table.toControl(e.x, e.y);
                    e.feedback |= pt.y < b.y + b.height / 2 ? DND.FEEDBACK_INSERT_BEFORE : DND.FEEDBACK_INSERT_AFTER;
                }
            }

            @Override
            public void drop(DropTargetEvent e) {
                SectionRef s = store.selectedSection();
                if (!(LocalTransfer.payload instanceof PageRef p) || s == null || !p.section().equals(s)) return;
                TableItem item = (TableItem) e.item;
                List<PageMeta> visible = store.visiblePages(s);
                String before = null;
                int level = -1;
                if (item != null && item.getData() instanceof PageMeta over) {
                    Rectangle b = item.getBounds();
                    boolean after = table.toControl(e.x, e.y).y >= b.y + b.height / 2;
                    int i = visible.indexOf(over);
                    int target = after ? i + 1 : i;
                    // Dropped next to a subpage, the page becomes one.
                    PageMeta neighbor = after ? over : (i > 0 ? visible.get(i - 1) : null);
                    level = neighbor != null && neighbor.level > 0 ? neighbor.level : 0;
                    if (after && over.collapsed) {
                        while (target < visible.size() && visible.get(target).level > over.level) target++;
                    }
                    before = target < visible.size() ? visible.get(target).id : null;
                    if (p.pageId().equals(before)) return;
                }
                store.movePages(s, List.of(p.pageId()), before, level);
            }
        });
    }

}
