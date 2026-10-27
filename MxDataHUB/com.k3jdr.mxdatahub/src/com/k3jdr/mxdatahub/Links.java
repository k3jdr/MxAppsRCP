package com.k3jdr.mxdatahub;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.SecurityLink;

/**
 * Links on a security's ticker and name (Shared/Links, HubLinks.swift), kept in srm.security_link (migration V014)
 * through the app's connection, so MxAcctMGR shows the same links and its links show here. A linked cell shows its
 * words in the link colour; a click opens it; resting the pointer shows its hover text and where it goes (the hover
 * card's text); the row's context menu edits, copies or removes it.
 */
final class Links {
    /** What a linked table row is: the security and its ticker, name and type. */
    record Target(long securityId, String ticker, String name, String type) {}

    private final Hub hub;
    private final Map<Long, Map<String, SecurityLink>> cache = new HashMap<>();
    private Boolean available;

    Links(Hub hub) {
        this.hub = hub;
    }

    /** Forgets what it knew (a new connection). */
    void reset() {
        cache.clear();
        available = null;
    }

    SecurityLink get(long id, String field) {
        Map<String, SecurityLink> m = cache.get(id);
        return m == null ? null : m.get(field);
    }

    /** Loads the links of these securities, then runs {@code done} (to redraw the table). */
    void load(Table owner, List<Long> ids, Runnable done) {
        if (!hub.isConnected() || ids.isEmpty() || Boolean.FALSE.equals(available)) return;
        fetch(owner, ids, done);
    }

    private void fetch(Table owner, List<Long> ids, Runnable done) {
        com.k3jdr.mx.core.ui.Background.run(owner, () -> {
            if (available == null) available = hub.db.hasLinks();
            return available ? hub.db.links(ids) : Map.<Long, Map<String, SecurityLink>>of();
        }, links -> {
            for (Long id : ids) cache.remove(id);
            cache.putAll(links);
            done.run();
        }, t -> { /* links are extra: a table without them still works */ });
    }

    /**
     * Shows the links of a table's ticker and name columns: the words in the link colour, a click opens it, the
     * pointer shows its hover text.
     */
    void decorate(Table table, int tickerColumn, int nameColumn, Function<Object, Target> target) {
        table.addListener(SWT.MouseUp, e -> {
            if (e.button != 1 || (e.stateMask & SWT.MODIFIER_MASK) != 0) return;
            Hit hit = hit(table, new Point(e.x, e.y), tickerColumn, nameColumn, target);
            if (hit != null && hit.link() != null) open(hit.link());
        });
        table.addListener(SWT.MouseMove, e -> {
            Hit hit = hit(table, new Point(e.x, e.y), tickerColumn, nameColumn, target);
            String tip = hit == null || hit.link() == null ? null : tip(hit.link());
            if (!java.util.Objects.equals(tip, table.getToolTipText())) table.setToolTipText(tip);
            table.setCursor(hit != null && hit.link() != null ? table.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
        });
    }

    /** Applies the links' words and colour to the table's rows (after filling it or loading the links). */
    void paint(Table table, int tickerColumn, int nameColumn, Function<Object, Target> target) {
        if (table.isDisposed()) return;
        for (TableItem item : table.getItems()) {
            Target t = target.apply(item.getData());
            if (t == null) continue;
            paintCell(item, tickerColumn, get(t.securityId(), "ticker"), t.ticker());
            paintCell(item, nameColumn, get(t.securityId(), "name"), t.name());
        }
    }

    private static void paintCell(TableItem item, int column, SecurityLink link, String plain) {
        if (column < 0) return;
        if (link == null) {
            item.setForeground(column, null);
            item.setText(column, plain == null ? "" : plain);
        } else {
            item.setForeground(column, Ui.ACCENT);
            String words = link.text() == null || link.text().isBlank() ? plain : link.text();
            item.setText(column, words == null ? "" : words);
        }
    }

    private record Hit(Target target, String field, SecurityLink link) {}

    private Hit hit(Table table, Point p, int tickerColumn, int nameColumn, Function<Object, Target> target) {
        TableItem item = table.getItem(p);
        if (item == null) return null;
        Target t = target.apply(item.getData());
        if (t == null) return null;
        String field = null;
        if (tickerColumn >= 0 && item.getBounds(tickerColumn).contains(p)) field = "ticker";
        else if (nameColumn >= 0 && item.getBounds(nameColumn).contains(p)) field = "name";
        if (field == null) return null;
        return new Hit(t, field, get(t.securityId(), field));
    }

    /** The field under the pointer ("ticker" or "name"), for Ctrl+K: it takes the column the pointer rests on. */
    String fieldAt(Table table, int tickerColumn, int nameColumn) {
        Point p = table.toControl(table.getDisplay().getCursorLocation());
        TableItem item = table.getItem(p);
        if (item == null) return null;
        if (nameColumn >= 0 && item.getBounds(nameColumn).contains(p)) return "name";
        if (tickerColumn >= 0 && item.getBounds(tickerColumn).contains(p)) return "ticker";
        return null;
    }

    static String tip(SecurityLink link) {
        StringBuilder sb = new StringBuilder();
        if (link.hover() != null && !link.hover().isBlank()) sb.append(link.hover().trim()).append("\n\n");
        if (link.title() != null && !link.title().isBlank()) sb.append(link.title()).append(" — ");
        sb.append(link.address());
        return sb.toString();
    }

    static void open(SecurityLink link) {
        String url = address(link.address());
        if (url != null) Program.launch(url);
    }

    /** A link's address as typed: a web address (https:// is added), an email address, or any link with its scheme. */
    static String address(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.isEmpty()) return null;
        if (t.contains("://") || t.startsWith("mailto:")) return t;
        if (t.contains("@") && !t.contains(" ")) return "mailto:" + t;
        return "https://" + t;
    }

    /**
     * Adds the link items for the row under the pointer to a context menu: Add / Edit Link on Ticker… and Name…,
     * Copy Link Address, Remove Link (as the hover card's buttons).
     */
    void addMenuItems(Menu menu, Table table, int tickerColumn, int nameColumn, Function<Object, Target> target, Runnable changed) {
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) if (mi.getData() == this) mi.dispose();
            if (!hub.isConnected() || Boolean.FALSE.equals(available)) return;
            TableItem[] sel = table.getSelection();
            if (sel.length == 0) return;
            Target t = target.apply(sel[0].getData());
            if (t == null) return;
            int at = 0;
            for (String field : List.of("ticker", "name")) {
                SecurityLink link = get(t.securityId(), field);
                String what = field.equals("ticker") ? "Ticker" : "Name";
                at = add(menu, at, (link == null ? "Add Link to " : "Edit Link on ") + what + "…",
                        () -> edit(table, t, field, changed));
                if (link != null) {
                    at = add(menu, at, "Open Link on " + what, () -> open(link));
                    at = add(menu, at, "Copy Link on " + what, () -> Clipboards.copyText(table.getDisplay(), address(link.address())));
                    at = add(menu, at, "Remove Link on " + what, () -> remove(table, t, field, changed));
                }
            }
            MenuItem sep = new MenuItem(menu, SWT.SEPARATOR, at);
            sep.setData(this);
        });
    }

    private int add(Menu menu, int index, String text, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH, index);
        mi.setText(Ui.escape(text));
        mi.setData(this);
        mi.addListener(SWT.Selection, e -> action.run());
        return index + 1;
    }

    /** The Link dialog for a field of a security; saves or removes in srm.security_link. */
    void edit(Table owner, Target t, String field, Runnable changed) {
        if (!hub.isConnected()) return;
        if (Boolean.FALSE.equals(available)) {
            hub.error("Links need the V014 migration (srm.security_link): run SRM/srm.py migrate.");
            return;
        }
        LinkDialog d = new LinkDialog(owner.getShell(), t, field, get(t.securityId(), "ticker"), get(t.securityId(), "name"));
        if (d.open() != LinkDialog.OK) return;
        long id = t.securityId();
        String f = d.field();
        SecurityLink link = d.result();
        hub.run(owner, () -> {
            if (link == null) hub.db.deleteLink(id, f);
            else hub.db.saveLink(id, f, link);
            return hub.db.links(List.of(id));
        }, links -> {
            cache.remove(id);
            cache.putAll(links);
            hub.info(link == null ? "Removed the link on " + t.ticker() + "'s " + f : "Saved the link on " + t.ticker() + "'s " + f);
            changed.run();
        });
    }

    private void remove(Table owner, Target t, String field, Runnable changed) {
        long id = t.securityId();
        hub.run(owner, () -> {
            hub.db.deleteLink(id, field);
            return hub.db.links(List.of(id));
        }, links -> {
            cache.remove(id);
            cache.putAll(links);
            changed.run();
        });
    }
}
