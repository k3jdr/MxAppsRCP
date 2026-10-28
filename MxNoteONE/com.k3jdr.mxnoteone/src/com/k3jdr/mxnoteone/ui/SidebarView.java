package com.k3jdr.mxnoteone.ui;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxnoteone.model.Kinds.NoteColor;
import com.k3jdr.mxnoteone.model.SectionNode;
import com.k3jdr.mxnoteone.store.Notebook;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Refs.SectionRef;
import com.k3jdr.mxnoteone.store.Templates;

/**
 * The sidebar (SidebarView.swift): open notebooks with their sections (coloured tabs) and section groups (folders
 * that nest), the search field, + for a section, group or notebook, and the Tag Summary and Recycle Bin buttons.
 * Right-click for each one's commands; drag a section or group to reorder it, onto a group to put it inside, or
 * onto the notebook to put it last; drop a page on a section to move it there.
 */
final class SidebarView {
    /** A row: a notebook (node null) or a section / group in it. */
    record Node(String notebookId, String nodeId) {}

    final Composite root;
    final Text search;
    final Tree tree;
    private final NotebookStore store = NotebookStore.get();
    private final Commands commands;
    private boolean rebuilding;
    Runnable showTags = () -> {}, showTrash = () -> {};

    SidebarView(Composite parent, Commands commands) {
        this.commands = commands;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(Ui.grid(1, 0, 0));
        root.setBackground(Ui.SIDEBAR);
        search = new Text(root, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        search.setMessage("Search All Notebooks");
        GridData sg = Ui.fillH();
        sg.horizontalIndent = 6;
        sg.widthHint = 120;
        search.setLayoutData(sg);
        QuickHelp.help(search, "Search All Notebooks", "Finds pages in every open notebook by their title and text (locked sections aside). The page list shows the results.", "Ctrl+E");

        tree = new Tree(root, SWT.SINGLE | SWT.FULL_SELECTION | SWT.NO_SCROLL | SWT.V_SCROLL);
        tree.setLayoutData(Ui.fill());
        tree.setBackground(Ui.SIDEBAR);
        tree.addListener(SWT.Selection, e -> selected());
        tree.addListener(SWT.Expand, e -> expanded((TreeItem) e.item, true));
        tree.addListener(SWT.Collapse, e -> expanded((TreeItem) e.item, false));
        Menu menu = new Menu(tree);
        menu.addListener(SWT.Show, e -> fillMenu(menu));
        tree.setMenu(menu);
        QuickHelp.provider(tree, (c, p) -> describe(p));
        dragAndDrop();

        ToolBar bar = new ToolBar(root, SWT.FLAT | SWT.RIGHT);
        bar.setLayoutData(Ui.fillH());
        bar.setBackground(Ui.SIDEBAR);
        ToolItem add = new ToolItem(bar, SWT.DROP_DOWN);
        add.setText("+  Add");
        Menu addMenu = new Menu(bar);
        ElementView.item(addMenu, "New Section", store::addSectionToCurrentNotebook);
        ElementView.item(addMenu, "New Section Group", () -> {
            Notebook nb = store.currentNotebook();
            if (nb != null) commands.newSectionGroup(nb.id(), null);
        });
        new MenuItem(addMenu, SWT.SEPARATOR);
        ElementView.item(addMenu, "New Notebook…", () -> Dialogs.newNotebook(root.getShell(), store));
        ElementView.item(addMenu, "Open Notebook…", commands::openNotebook);
        add.addListener(SWT.Selection, e -> {
            Rectangle r = add.getBounds();
            Point p = bar.toDisplay(r.x, r.y + r.height);
            addMenu.setLocation(p);
            addMenu.setVisible(true);
        });
        ToolItem tags = new ToolItem(bar, SWT.PUSH);
        tags.setText("Tags");
        tags.addListener(SWT.Selection, e -> showTags.run());
        ToolItem trash = new ToolItem(bar, SWT.PUSH);
        trash.setText("Recycle Bin");
        trash.addListener(SWT.Selection, e -> showTrash.run());
        QuickHelp.provider(bar, (c, p) -> {
            ToolItem it = bar.getItem(p);
            if (it == add) return new QuickHelpItem("Add", "New section, section group or notebook, or open a notebook folder.");
            if (it == tags) return new QuickHelpItem("Tag Summary", "Opens a window listing every tagged paragraph in your notebooks.", "Ctrl+Shift+T");
            if (it == trash) return new QuickHelpItem("Recycle Bin", "Deleted pages and sections, kept for 60 days. Restore them from here.");
            return null;
        });
        store.addListener(c -> {
            if (c == NotebookStore.Change.TREE) rebuild();
            else if (c == NotebookStore.Change.SELECTION) syncSelection();
        });
    }

    // MARK: Tree

    void rebuild() {
        if (tree.isDisposed()) return;
        rebuilding = true;
        tree.setRedraw(false);
        try {
            int top = tree.getTopItem() == null ? 0 : tree.indexOf(tree.getTopItem());
            tree.removeAll();
            TreeItem select = null;
            for (Notebook nb : store.notebooks) {
                TreeItem item = new TreeItem(tree, SWT.NONE);
                item.setText(nb.name() + (nb.manifest.isOneDrive() ? "  ☁" : ""));
                item.setImage(Gfx.book(nb.manifest.color.rgb));
                item.setFont(Gfx.uiFont(0, SWT.BOLD));
                item.setData(new Node(nb.id(), null));
                TreeItem s = fill(item, nb, nb.manifest.sections);
                if (s != null) select = s;
                item.setExpanded(store.expandedNotebooks.contains(nb.id()));
            }
            if (select != null) tree.setSelection(select);
            if (top > 0 && top < tree.getItemCount()) tree.setTopItem(tree.getItem(top));
        } finally {
            tree.setRedraw(true);
            rebuilding = false;
        }
    }

    /** Shows the selected section (or group) as selected, without building the tree again. */
    private void syncSelection() {
        if (tree.isDisposed()) return;
        SectionRef sel = store.selectedSection() != null ? store.selectedSection() : store.selectedGroup();
        if (sel == null) return;
        TreeItem found = find(tree.getItems(), new Node(sel.notebookId(), sel.sectionId()));
        if (found == null) {
            rebuild();
            return;
        }
        if (tree.getSelectionCount() == 1 && tree.getSelection()[0] == found) return;
        rebuilding = true;
        try {
            for (TreeItem p = found.getParentItem(); p != null; p = p.getParentItem()) p.setExpanded(true);
            tree.setSelection(found);
        } finally {
            rebuilding = false;
        }
    }

    private static TreeItem find(TreeItem[] items, Node n) {
        for (TreeItem i : items) {
            if (n.equals(i.getData())) return i;
            TreeItem f = find(i.getItems(), n);
            if (f != null) return f;
        }
        return null;
    }

    private TreeItem fill(TreeItem parent, Notebook nb, List<SectionNode> nodes) {
        TreeItem select = null;
        SectionRef sel = store.selectedSection() != null ? store.selectedSection() : store.selectedGroup();
        for (SectionNode n : nodes) {
            TreeItem item = new TreeItem(parent, SWT.NONE);
            item.setText(n.name);
            SectionRef ref = new SectionRef(nb.id(), n.id);
            item.setImage(n.group ? Gfx.folder() : n.isProtected() && store.isLocked(ref) ? Gfx.lock(n.color.rgb) : Gfx.swatch(n.color.rgb));
            item.setData(new Node(nb.id(), n.id));
            if (sel != null && sel.equals(ref)) select = item;
            if (n.group && n.children != null) {
                TreeItem s = fill(item, nb, n.children);
                if (s != null) select = s;
                item.setExpanded(store.expandedGroups.contains(n.id));
            }
        }
        if (select != null) {
            for (TreeItem p = select.getParentItem(); p != null; p = p.getParentItem()) p.setExpanded(true);
        }
        return select;
    }

    private void expanded(TreeItem item, boolean open) {
        if (rebuilding || !(item.getData() instanceof Node n)) return;
        if (n.nodeId == null) {
            if (open) store.expandedNotebooks.add(n.notebookId);
            else store.expandedNotebooks.remove(n.notebookId);
        } else if (open) {
            store.expandedGroups.add(n.nodeId);
        } else {
            store.expandedGroups.remove(n.nodeId);
        }
    }

    private Node selectedNode() {
        TreeItem[] sel = tree.getSelection();
        return sel.length == 1 && sel[0].getData() instanceof Node n ? n : null;
    }

    private void selected() {
        if (rebuilding) return;
        Node n = selectedNode();
        if (n == null || n.nodeId == null) return;
        SectionRef ref = new SectionRef(n.notebookId, n.nodeId);
        SectionNode node = store.node(ref);
        if (node == null) return;
        if (!search.getText().isEmpty()) search.setText("");
        if (node.group) store.selectGroup(ref);
        else store.selectSection(ref);
    }

    private QuickHelpItem describe(Point p) {
        TreeItem item = tree.getItem(p);
        if (item == null || !(item.getData() instanceof Node n)) return null;
        if (n.nodeId == null) return new QuickHelpItem("Notebook: " + item.getText(),
                "A notebook and its sections. Right-click it to add sections, rename, recolour, copy a link, save it elsewhere or close it.");
        SectionNode node = store.node(new SectionRef(n.notebookId, n.nodeId));
        if (node == null) return null;
        if (node.group) return new QuickHelpItem("Section Group: " + node.name,
                "A folder of sections (groups can nest). Drag sections onto it to put them inside; right-click for its commands.");
        return new QuickHelpItem("Section: " + node.name, (node.isProtected() ? "Password protected. " : "")
                + "Click to show its pages. Drag it to reorder, onto a group to put it inside; drop a page on it to move the page here. Right-click to rename, recolour, move, protect or delete it.");
    }

    // MARK: Menus

    private void fillMenu(Menu menu) {
        for (MenuItem mi : menu.getItems()) mi.dispose();
        Node n = selectedNode();
        TreeItem under = tree.getItem(tree.toControl(tree.getDisplay().getCursorLocation()));
        if (under != null && under.getData() instanceof Node u) {
            n = u;
            tree.setSelection(under);
        }
        if (n == null) {
            ElementView.item(menu, "New Notebook…", () -> Dialogs.newNotebook(root.getShell(), store));
            ElementView.item(menu, "Open Notebook…", commands::openNotebook);
            return;
        }
        Notebook nb = store.notebook(n.notebookId);
        if (nb == null) return;
        String nbId = nb.id();
        if (n.nodeId == null) {
            ElementView.item(menu, "New Section", () -> store.addSection(nbId, null, null, Templates.BLANK));
            ElementView.item(menu, "New Section Group", () -> commands.newSectionGroup(nbId, null));
            new MenuItem(menu, SWT.SEPARATOR);
            ElementView.item(menu, "Rename Notebook…", () -> commands.renameNotebook(nbId));
            ElementView.item(menu, "Copy Link to this Notebook", () -> Clipboards.copyText(tree.getDisplay(), NotebookStore.notebookLink(nbId)));
            colorMenu(menu, "Notebook Color", nb.manifest.color, c -> store.setNotebookColor(nbId, c));
            new MenuItem(menu, SWT.SEPARATOR);
            ElementView.item(menu, "Show in Explorer", () -> org.eclipse.swt.program.Program.launch(nb.folder.toString()));
            ElementView.item(menu, "Save Notebook As…", () -> commands.saveNotebookAs(nbId));
            ElementView.item(menu, "Recycle Bin", showTrash);
            new MenuItem(menu, SWT.SEPARATOR);
            ElementView.item(menu, "Close Notebook", () -> store.closeNotebook(nbId));
            return;
        }
        SectionRef ref = new SectionRef(nbId, n.nodeId);
        SectionNode node = store.node(ref);
        if (node == null) return;
        if (node.group) {
            ElementView.item(menu, "New Section in Group", () -> store.addSection(nbId, node.id, null, Templates.BLANK));
            ElementView.item(menu, "New Section Group in Group", () -> commands.newSectionGroup(nbId, node.id));
            new MenuItem(menu, SWT.SEPARATOR);
            ElementView.item(menu, "Rename Section Group…", () -> commands.renameNode(ref));
            ElementView.item(menu, "Copy Link to this Section Group", () -> Clipboards.copyText(tree.getDisplay(), NotebookStore.sectionLink(ref)));
        } else {
            ElementView.item(menu, "New Page", () -> {
                store.selectSection(ref);
                store.addPageToCurrentSection(Templates.BLANK, false);
            }).setEnabled(!store.isLocked(ref));
            new MenuItem(menu, SWT.SEPARATOR);
            ElementView.item(menu, "Rename Section…", () -> commands.renameNode(ref));
            ElementView.item(menu, "Copy Link to this Section", () -> Clipboards.copyText(tree.getDisplay(), NotebookStore.sectionLink(ref)));
            colorMenu(menu, "Section Color", node.color, c -> commands.colorNode(ref, c));
            new MenuItem(menu, SWT.SEPARATOR);
            if (node.isProtected()) {
                if (store.isLocked(ref)) {
                    ElementView.item(menu, "Unlock Section…", () -> commands.unlock(ref));
                } else {
                    ElementView.item(menu, "Lock Section", () -> store.lock(ref));
                    ElementView.item(menu, "Change Password…", () -> commands.protect(ref));
                    ElementView.item(menu, "Remove Password…", () -> commands.removePassword(ref));
                }
            } else {
                ElementView.item(menu, "Password Protect Section…", () -> commands.protect(ref));
            }
        }
        new MenuItem(menu, SWT.SEPARATOR);
        ElementView.item(menu, "Move Up", () -> store.moveNode(ref, -1));
        ElementView.item(menu, "Move Down", () -> store.moveNode(ref, 1));
        MenuItem move = new MenuItem(menu, SWT.CASCADE);
        move.setText("Move to Group");
        Menu mm = new Menu(move);
        move.setMenu(mm);
        ElementView.item(mm, "Top Level of Notebook", () -> store.moveNodeToGroup(ref, null));
        for (SectionNode g : SectionNode.groups(nb.manifest.sections)) {
            if (g.id.equals(node.id) || node.children != null && SectionNode.find(node.children, g.id) != null) continue;
            List<String> path = SectionNode.path(nb.manifest.sections, g.id);
            String label = (path == null || path.isEmpty() ? "" : String.join(" › ", path) + " › ") + g.name;
            ElementView.item(mm, label, () -> store.moveNodeToGroup(ref, g.id));
        }
        new MenuItem(menu, SWT.SEPARATOR);
        ElementView.item(menu, node.group ? "Delete Section Group" : "Delete Section", () -> commands.deleteNode(ref));
    }

    private void colorMenu(Menu menu, String title, NoteColor current, java.util.function.Consumer<NoteColor> pick) {
        MenuItem mi = new MenuItem(menu, SWT.CASCADE);
        mi.setText(title);
        Menu sub = new Menu(mi);
        mi.setMenu(sub);
        for (NoteColor c : NoteColor.values()) {
            MenuItem x = new MenuItem(sub, SWT.RADIO);
            x.setText(c.title());
            x.setImage(Gfx.swatch(c.rgb));
            x.setSelection(c == current);
            x.addListener(SWT.Selection, e -> {
                if (x.getSelection()) pick.accept(c);
            });
        }
    }

    // MARK: Drag and drop

    private void dragAndDrop() {
        DragSource source = new DragSource(tree, DND.DROP_MOVE);
        source.setTransfer(LocalTransfer.get());
        source.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent e) {
                Node n = selectedNode();
                e.doit = n != null && n.nodeId != null;
                LocalTransfer.payload = n;
            }

            @Override
            public void dragSetData(DragSourceEvent e) {
                e.data = LocalTransfer.payload;
            }
        });
        DropTarget target = new DropTarget(tree, DND.DROP_MOVE);
        target.setTransfer(new Transfer[] { LocalTransfer.get() });
        target.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetEvent e) {
                e.feedback = DND.FEEDBACK_SCROLL | DND.FEEDBACK_EXPAND;
                TreeItem item = (TreeItem) e.item;
                if (item == null || !(item.getData() instanceof Node over)) {
                    e.detail = DND.DROP_NONE;
                    return;
                }
                Object p = LocalTransfer.payload;
                if (p instanceof PageRef) {
                    boolean section = over.nodeId != null && store.node(new SectionRef(over.notebookId, over.nodeId)) != null
                            && !store.node(new SectionRef(over.notebookId, over.nodeId)).group;
                    e.detail = section ? DND.DROP_MOVE : DND.DROP_NONE;
                    e.feedback |= DND.FEEDBACK_SELECT;
                    return;
                }
                if (!(p instanceof Node dragged) || !dragged.notebookId.equals(over.notebookId) || dragged.equals(over)) {
                    e.detail = DND.DROP_NONE;
                    return;
                }
                e.detail = DND.DROP_MOVE;
                e.feedback |= where(item, e) ;
            }

            @Override
            public void drop(DropTargetEvent e) {
                TreeItem item = (TreeItem) e.item;
                if (item == null || !(item.getData() instanceof Node over)) return;
                Object p = LocalTransfer.payload;
                if (p instanceof PageRef page) {
                    if (over.nodeId != null) store.movePage(page, new SectionRef(over.notebookId, over.nodeId), false, tree.getDisplay());
                    return;
                }
                if (!(p instanceof Node dragged) || !dragged.notebookId.equals(over.notebookId)) return;
                SectionRef ref = new SectionRef(dragged.notebookId, dragged.nodeId);
                int feedback = where(item, e);
                if (over.nodeId == null) {
                    store.moveNodeToGroup(ref, null);
                } else if (feedback == DND.FEEDBACK_SELECT) {
                    store.moveNodeToGroup(ref, over.nodeId);
                } else {
                    store.moveNodeBeside(ref, over.nodeId, feedback == DND.FEEDBACK_INSERT_AFTER);
                }
            }
        });
    }

    /** Before, after, or into (a group or the notebook) the row the pointer is over. */
    private int where(TreeItem item, DropTargetEvent e) {
        Node over = (Node) item.getData();
        Rectangle b = item.getBounds();
        Point p = tree.toControl(e.x, e.y);
        if (over.nodeId == null) return DND.FEEDBACK_SELECT;
        SectionNode node = store.node(new SectionRef(over.notebookId, over.nodeId));
        boolean group = node != null && node.group;
        if (group && p.y > b.y + b.height / 4 && p.y < b.y + b.height * 3 / 4) return DND.FEEDBACK_SELECT;
        return p.y < b.y + b.height / 2 ? DND.FEEDBACK_INSERT_BEFORE : DND.FEEDBACK_INSERT_AFTER;
    }
}
