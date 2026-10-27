package com.k3jdr.mxjobsmgr.ui;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.MainPart;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.StepLink;

/**
 * The palette (PaletteView.swift): node kinds grouped by layer — drag one onto the canvas, or double-click it to add it
 * at the end of its lane; right-click to rename it (Edit Element…, or Enter) or give it a link — and the shared Layer 3
 * services with their state (double-click adds a step, right-click picks an endpoint).
 */
public final class Palette {
    private final MainPart main;
    final Tree tree;
    private final Map<String, Boolean> running = new HashMap<>();
    private static final String SERVICES = "services";

    public Palette(Composite parent, MainPart main) {
        this.main = main;
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(Ui.grid(1, 0, 4));
        root.setLayoutData(Ui.fill());
        tree = new Tree(root, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        tree.setLayoutData(Ui.fill());
        tree.setBackground(Ui.SIDEBAR);
        build();
        tree.addListener(SWT.MeasureItem, e -> {
            e.height = 40;
            e.width = Math.max(10, tree.getClientArea().width - e.x - 4);
        });
        tree.addListener(SWT.EraseItem, e -> e.detail &= ~SWT.FOREGROUND);
        tree.addListener(SWT.PaintItem, this::paintItem);
        tree.addListener(SWT.MouseDoubleClick, e -> {
            TreeItem item = tree.getItem(new Point(e.x, e.y));
            if (item == null) return;
            if (item.getData() instanceof NodeKind kind) main.withEditor(ed -> ed.add(kind));
            else if (item.getData() instanceof ServiceCatalog.Service s) main.withEditor(ed -> ed.addServiceStep(s, null));
        });
        tree.addListener(SWT.KeyDown, e -> {
            if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && selected() instanceof NodeKind kind) {
                e.doit = false;
                main.editElement(kind);
            }
        });
        tree.addListener(SWT.MenuDetect, this::menu);
        tree.addListener(SWT.MouseHover, e -> {
            TreeItem item = tree.getItem(new Point(e.x, e.y));
            tree.setToolTipText(item == null ? null : tooltip(item.getData()));
        });
        QuickHelp.provider(tree, (c, p) -> {
            TreeItem item = tree.getItem(p);
            if (item == null) return JobsHelp.PALETTE;
            Object d = item.getData();
            if (d instanceof NodeKind kind) return JobsHelp.paletteKind(kind);
            if (d instanceof Layer layer) return JobsHelp.paletteLayer(layer);
            if (d instanceof ServiceCatalog.Service s) return JobsHelp.paletteService(s, Boolean.TRUE.equals(running.get(s.id())));
            return new QuickHelpItem("Layer 3 Services", "The shared services (Services/) the workflows call, and whether each runs on this PC. Double-click one to add a step calling it.");
        });

        DragSource drag = new DragSource(tree, DND.DROP_COPY | DND.DROP_MOVE);
        drag.setTransfer(new Transfer[] { TextTransfer.getInstance() });
        drag.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent e) {
                e.doit = selected() instanceof NodeKind;
            }

            @Override
            public void dragSetData(DragSourceEvent e) {
                if (selected() instanceof NodeKind kind) e.data = DiagramCanvas.DRAG_PREFIX + kind.raw();
            }
        });

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(Ui.grid(2, 4, 4));
        buttons.setLayoutData(Ui.fillH());
        var services = Ui.button(buttons, "Layer 3 Services…", () -> main.showServices(null));
        services.setLayoutData(Ui.fillH());
        QuickHelp.help(services, "Layer 3 Services…", JobsHelp.SERVICES.detail(), "Ctrl+Alt+L");
        var samples = Ui.button(buttons, "New from Sample…", main::newFromSample);
        samples.setLayoutData(Ui.fillH());
        QuickHelp.help(samples, "New from Sample…", JobsHelp.SAMPLES.detail(), "Ctrl+Alt+N");
        PaletteEdits.addListener(() -> {
            if (!tree.isDisposed()) tree.redraw();
        });
        probe();
    }

    private void build() {
        for (Layer layer : Layer.values()) {
            TreeItem head = new TreeItem(tree, SWT.NONE);
            head.setData(layer);
            head.setText("Layer " + layer.number + " · " + layer.title);
            for (NodeKind kind : NodeKind.in(layer)) {
                TreeItem it = new TreeItem(head, SWT.NONE);
                it.setData(kind);
                it.setText(kind.title);
            }
            head.setExpanded(true);
        }
        TreeItem head = new TreeItem(tree, SWT.NONE);
        head.setData(SERVICES);
        head.setText("Layer 3 Services");
        for (ServiceCatalog.Service s : ServiceCatalog.ALL) {
            TreeItem it = new TreeItem(head, SWT.NONE);
            it.setData(s);
            it.setText(s.name());
        }
        head.setExpanded(true);
    }

    /** Asks the services whether they run (off the UI thread). */
    public void probe() {
        Background.run(tree, ServiceCatalog::probeAll, states -> {
            running.putAll(states);
            tree.redraw();
        }, err -> { });
    }

    private Object selected() {
        TreeItem[] sel = tree.getSelection();
        return sel.length == 0 ? null : sel[0].getData();
    }

    private String tooltip(Object d) {
        if (d instanceof NodeKind kind) {
            StepLink link = PaletteEdits.link(PaletteEdits.kindItem(kind));
            return link != null ? linkText(link, PaletteEdits.title(kind)) : "Drag onto the canvas, or double-click to add; right-click to edit it or give it a link";
        }
        if (d instanceof Layer layer) {
            StepLink link = PaletteEdits.link(PaletteEdits.layerItem(layer));
            return link != null ? linkText(link, "Layer " + layer.number + " · " + layer.title) : layer.subtitle;
        }
        if (d instanceof ServiceCatalog.Service s) return s.purpose() + "\n\nDouble-click to add a step; right-click for each endpoint.";
        return null;
    }

    private static String linkText(StepLink link, String words) {
        String tip = link.tipPlain();
        return (link.text != null ? link.text : words) + (link.title != null ? " — " + link.title : "") + (tip != null ? "\n" + tip : "") + "\n" + link.address;
    }

    private void paintItem(Event e) {
        TreeItem item = (TreeItem) e.item;
        Object d = item.getData();
        GC gc = e.gc;
        Rectangle b = item.getBounds();
        int x = b.x + 4, y = b.y;
        boolean selected = (e.detail & SWT.SELECTED) != 0;
        if (d instanceof Layer layer) {
            gc.setFont(Ui.bold());
            gc.setForeground(Colors.layer(layer));
            gc.drawText(item.getText(), x, y + 12, true);
            if (PaletteEdits.link(PaletteEdits.layerItem(layer)) != null) gc.drawText("🔗", b.x + b.width - 22, y + 12, true);
        } else if (d instanceof String) {
            gc.setFont(Ui.bold());
            gc.setForeground(Colors.layer(Layer.DATA));
            gc.drawText(item.getText(), x, y + 12, true);
        } else if (d instanceof NodeKind kind) {
            gc.setFont(Ui.font(2, SWT.BOLD));
            gc.setForeground(Colors.layer(kind.layer));
            gc.drawText(kind.symbol, x, y + 9, true);
            gc.setFont(Ui.font(0, SWT.NORMAL));
            gc.setForeground(selected ? Colors.of(Colors.TEXT) : Colors.of(Colors.TEXT));
            gc.drawText(PaletteEdits.title(kind), x + 24, y + 3, true);
            gc.setFont(Ui.small());
            gc.setForeground(Colors.of(Colors.SECONDARY));
            int room = tree.getClientArea().width - (x + 24) - 6;
            gc.drawText(DiagramPainter.clip(gc, PaletteEdits.summary(kind), room), x + 24, y + 21, true);
            if (PaletteEdits.link(PaletteEdits.kindItem(kind)) != null) {
                gc.setForeground(Colors.of(Colors.ACCENT));
                gc.drawText("🔗", tree.getClientArea().width - 22, y + 3, true);
            }
        } else if (d instanceof ServiceCatalog.Service s) {
            boolean on = Boolean.TRUE.equals(running.get(s.id()));
            gc.setBackground(on ? Ui.GREEN : Colors.of(Colors.TERTIARY));
            gc.setAntialias(SWT.ON);
            gc.fillOval(x + 4, y + 9, 7, 7);
            gc.setFont(Ui.font(0, SWT.NORMAL));
            gc.setForeground(Colors.of(Colors.TEXT));
            gc.drawText(s.name(), x + 18, y + 3, true);
            int w = gc.textExtent(s.name()).x;
            gc.setFont(Ui.font(-2, SWT.BOLD));
            gc.setForeground(Colors.of(Colors.SECONDARY));
            gc.drawText(s.language(), x + 22 + w, y + 5, true);
            gc.setFont(Ui.small());
            int room = tree.getClientArea().width - (x + 18) - 6;
            gc.drawText(DiagramPainter.clip(gc, String.join(" · ", s.cases().stream().map(u -> u.title).toList()), room), x + 18, y + 21, true);
        }
    }

    private void menu(Event e) {
        Point p = tree.toControl(e.x, e.y);
        TreeItem item = e.detail == SWT.MENU_KEYBOARD ? (tree.getSelection().length > 0 ? tree.getSelection()[0] : null) : tree.getItem(p);
        Menu old = tree.getMenu();
        if (old != null) old.dispose();
        if (item == null) {
            e.doit = false;
            return;
        }
        Menu menu = new Menu(tree);
        MenuBarBuilder.MenuBuilder mb = new MenuBarBuilder.MenuBuilder(null, menu);
        Object d = item.getData();
        if (d instanceof NodeKind kind) {
            mb.item("Add to Layer " + kind.layer.number, 0, () -> main.withEditor(ed -> ed.add(kind)), main::hasEditor);
            mb.separator();
            mb.item("Edit Element…", () -> main.editElement(kind));
            if (PaletteEdits.isEdited(kind)) mb.item("Reset to " + kind.title, () -> PaletteEdits.reset(kind));
            mb.separator();
            linkItems(mb, PaletteEdits.kindItem(kind));
        } else if (d instanceof Layer layer) {
            linkItems(mb, PaletteEdits.layerItem(layer));
        } else if (d instanceof ServiceCatalog.Service s) {
            for (ServiceCatalog.Endpoint ep : s.endpoints()) {
                mb.item(Ui.escape("Add Step: " + ep.method() + " " + ep.path()), 0, () -> main.withEditor(ed -> ed.addServiceStep(s, ep)), main::hasEditor);
            }
            mb.separator();
            mb.item(Ui.escape("About " + s.name() + "…"), () -> main.showServices(s.id()));
        } else {
            mb.item("Check Services", this::probe);
            mb.item("Layer 3 Services…", () -> main.showServices(null));
        }
        tree.setMenu(menu);
        menu.setLocation(e.x, e.y);
        menu.setVisible(true);
        e.doit = false;
    }

    private void linkItems(MenuBarBuilder.MenuBuilder mb, String item) {
        StepLink link = PaletteEdits.link(item);
        mb.item(link == null ? "Add Link…" : "Edit Link…", () -> {
            StepLink edited = LinkDialog.edit(tree.getShell(), PaletteEdits.itemTitle(item),
                    item.startsWith("kind:") ? "An element of the palette" : "A layer of the palette", link, PaletteEdits.itemTitle(item),
                    "Kept on this PC with MxJobsMGR's palette: the pointer resting on " + PaletteEdits.itemTitle(item)
                            + " shows its hover text, and its link mark opens it.");
            if (edited != null) PaletteEdits.setLink(item, edited.address.isEmpty() ? null : edited);
        });
        if (link != null) {
            mb.item("Open Link", () -> LinkDialog.follow(link.address));
            mb.item("Remove Link", () -> PaletteEdits.setLink(item, null));
        }
    }

    public GridData layoutData() { return Ui.fill(); }
}
