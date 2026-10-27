package com.k3jdr.mx.core.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * The window's sidebar of screens, in groups (MxDataHUB's screens, MxAcctMGR's guide): a tree of items with
 * ids, each with its Quick Help. Selecting an item calls the listener with its id.
 */
public final class Sidebar {
    public record Entry(String id, String label, String detail, String shortcut) {}

    private final Tree tree;
    private final Map<String, TreeItem> items = new LinkedHashMap<>();
    private final List<Consumer<String>> listeners = new ArrayList<>();
    private boolean quiet;

    public Sidebar(Composite parent) {
        tree = new Tree(parent, SWT.SINGLE | SWT.FULL_SELECTION);
        tree.setBackground(Ui.SIDEBAR);
        tree.setLayoutData(Ui.fill());
        tree.addListener(SWT.Selection, e -> {
            if (quiet || !(e.item instanceof TreeItem ti) || !(ti.getData() instanceof Entry entry)) return;
            listeners.forEach(l -> l.accept(entry.id()));
        });
        QuickHelp.provider(tree, (c, p) -> {
            TreeItem ti = tree.getItem(p);
            if (ti != null && ti.getData() instanceof Entry e) return new QuickHelpItem(e.label(), e.detail(), e.shortcut());
            if (ti != null) return new QuickHelpItem(ti.getText(), "A group of screens. Click its arrow to collapse or expand it.");
            return null;
        });
    }

    public Tree tree() { return tree; }

    /** A group heading; its items are added with {@link #item(TreeItem, String, String, String, String)}. */
    public TreeItem group(String label) {
        TreeItem g = new TreeItem(tree, SWT.NONE);
        g.setText(label);
        g.setFont(Ui.bold());
        g.setForeground(Ui.SECONDARY);
        return g;
    }

    /** An item in {@code group} (or at the top level when null). */
    public TreeItem item(TreeItem group, String id, String label, String detail, String shortcut) {
        TreeItem ti = group == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(group, SWT.NONE);
        ti.setText(label);
        ti.setData(new Entry(id, label, detail, shortcut));
        items.put(id, ti);
        if (group != null) group.setExpanded(true);
        return ti;
    }

    public void expandAll() {
        for (TreeItem g : tree.getItems()) g.setExpanded(true);
    }

    public void onSelect(Consumer<String> listener) { listeners.add(listener); }

    /** Selects the item without telling the listeners (the caller already shows it). */
    public void select(String id) {
        TreeItem ti = items.get(id);
        if (ti == null) return;
        quiet = true;
        try {
            tree.setSelection(ti);
        } finally {
            quiet = false;
        }
    }

    public String selectedId() {
        TreeItem[] sel = tree.getSelection();
        return sel.length > 0 && sel[0].getData() instanceof Entry e ? e.id() : null;
    }

    public List<Entry> entries() {
        return items.values().stream().map(t -> (Entry) t.getData()).toList();
    }
}
