package com.k3jdr.mxnoteone.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxnoteone.model.Kinds;
import com.k3jdr.mxnoteone.model.Kinds.PageColor;
import com.k3jdr.mxnoteone.model.Kinds.RuleLines;
import com.k3jdr.mxnoteone.model.PageContent;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.model.PageMeta;
import com.k3jdr.mxnoteone.model.SwiftJson;
import com.k3jdr.mxnoteone.model.TableData;
import com.k3jdr.mxnoteone.store.Disk;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.PageIndexer;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Templates;
import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.RText;
import com.k3jdr.mxnoteone.text.RichCodec;

/**
 * The page (PageCanvasView + CanvasController): its title, the date it was created and updated, and a free-form
 * canvas of containers — click anywhere to start a text container, drag containers by their bar, resize them by
 * their right edge, drop pictures and files on it. It loads the page in the background, saves it a moment after
 * changes stop (as the Mac does), and keeps an Undo history while the page is open.
 */
public final class PageView {
    public static final double MARGIN = 48, CONTENT_TOP = 110, TITLE_WIDTH = 640;

    final NotebookStore store = NotebookStore.get();
    final Composite root;
    final ScrolledComposite scroll;
    final Composite paper;
    final Text title;
    final Label date;
    final Label empty;
    PageRef ref;
    PageContent content;
    double zoom = 1;
    final List<ElementView> views = new ArrayList<>();
    ElementView selected;
    private boolean dirty;
    private int saveGeneration;
    private int loadGeneration;
    final UndoStack undo = new UndoStack();
    private TextBox lastText;
    TableBox activeTable;
    private Point insertionPoint;
    public Runnable onFormatting = () -> {};
    public Runnable onTableActive = () -> {};
    public boolean snapToGrid;
    private String typingElement;
    private long typingTime;

    public PageView(Composite parent) {
        root = new Composite(parent, SWT.NONE);
        root.setLayout(new org.eclipse.swt.layout.FillLayout());
        scroll = new ScrolledComposite(root, SWT.H_SCROLL | SWT.V_SCROLL);
        scroll.setExpandHorizontal(false);
        scroll.setExpandVertical(false);
        paper = new Composite(scroll, SWT.NONE);
        paper.setLayout(null);
        paper.setBackgroundMode(SWT.INHERIT_FORCE);
        paper.addListener(SWT.Dispose, e -> {
            if (paperImage != null) paperImage.dispose();
        });
        scroll.setContent(paper);
        scroll.getVerticalBar().setIncrement(24);
        scroll.getHorizontalBar().setIncrement(24);
        scroll.addListener(SWT.Resize, e -> updateSize());

        title = new Text(paper, SWT.SINGLE);
        title.setMessage("Page Title");
        title.addListener(SWT.Modify, e -> {
            if (ref == null || loading) return;
            store.setTitle(ref, title.getText());
            markDirty();
        });
        title.addListener(SWT.DefaultSelection, e -> focusFirstText());
        title.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_TAB_NEXT) {
                e.doit = false;
                focusFirstText();
            }
        });
        QuickHelp.help(title, "Page Title", "The page's title, shown in the page list. Return goes on to the page's first text.");
        date = new Label(paper, SWT.NONE);
        QuickHelp.help(date, "Created and Updated", "When the page was created and, after the bar, when it was last changed. Rest the pointer here for both in words.");
        empty = new Label(paper, SWT.CENTER);
        empty.setForeground(Gfx.color(Gfx.GRAY_TEXT));

        paper.addPaintListener(e -> paint(e.gc, e.x, e.y, e.width, e.height));
        paper.addListener(SWT.MouseDown, this::paperMouseDown);
        paper.addListener(SWT.KeyDown, this::paperKey);
        paper.addListener(SWT.Traverse, e -> {
            if (selected != null && (e.detail == SWT.TRAVERSE_RETURN || e.detail == SWT.TRAVERSE_ESCAPE || e.detail == SWT.TRAVERSE_ARROW_NEXT
                    || e.detail == SWT.TRAVERSE_ARROW_PREVIOUS)) e.doit = false;
        });
        Menu menu = new Menu(paper);
        menu.addListener(SWT.Show, e -> fillPaperMenu(menu));
        paper.setMenu(menu);
        QuickHelp.provider(paper, (c, p) -> ref == null ? null : new QuickHelpItem("Page",
                "Click anywhere to start a text container there. Right-click for New Text Box Here, Paste Here and Insert Here. Drop pictures and files onto the page. Ctrl+mouse wheel zooms."));

        Listener hover = e -> trackHover();
        Display d = root.getDisplay();
        d.addFilter(SWT.MouseMove, hover);
        Listener wheel = e -> wheel(e);
        d.addFilter(SWT.MouseVerticalWheel, wheel);
        root.addListener(SWT.Dispose, e -> {
            d.removeFilter(SWT.MouseMove, hover);
            d.removeFilter(SWT.MouseVerticalWheel, wheel);
        });

        DropTarget drop = new DropTarget(paper, DND.DROP_COPY | DND.DROP_DEFAULT);
        drop.setTransfer(FileTransfer.getInstance(), ImageTransfer.getInstance());
        drop.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetEvent e) {
                e.detail = ref == null ? DND.DROP_NONE : DND.DROP_COPY;
            }

            @Override
            public void drop(DropTargetEvent e) {
                if (ref == null) return;
                Point p = paper.toControl(e.x, e.y);
                double[] at = { p.x / zoom, p.y / zoom };
                if (e.data instanceof String[] files) insertFiles(files, at);
                else if (e.data instanceof ImageData img) insertImageData(img, at);
            }
        });
        showEmpty("");
    }

    Color paperColor() {
        return Gfx.color(content != null ? content.color.rgb : PageColor.NONE.rgb);
    }

    int px(double v) { return (int) Math.round(v * zoom); }

    // MARK: Loading and saving

    private boolean loading;

    /** Shows a page (read in the background), saving the one shown first. */
    public void load(PageRef newRef) {
        if (newRef != null && newRef.equals(ref) && content != null) return;
        flush();
        int gen = ++loadGeneration;
        ref = newRef;
        clear();
        undo.clear();
        if (newRef == null) {
            content = null;
            showEmpty(store.selectedSection() != null && store.isLocked(store.selectedSection())
                    ? "This section is password protected. Unlock it from the sidebar (right-click ▸ Unlock Section…)." : "");
            return;
        }
        showEmpty("Loading…");
        Disk.submit(() -> {
            PageContent page = store.readPage(newRef);
            root.getDisplay().asyncExec(() -> {
                if (root.isDisposed() || gen != loadGeneration || !newRef.equals(ref)) return;
                if (page == null) {
                    showEmpty("");
                    return;
                }
                content = page;
                build();
                scroll.setOrigin(0, 0);
                List<Runnable> waiting = new ArrayList<>(whenLoaded);
                whenLoaded.clear();
                waiting.forEach(Runnable::run);
                if (store.focusTitleOnLoad) {
                    store.focusTitleOnLoad = false;
                    title.setFocus();
                }
            });
        });
    }

    private void showEmpty(String message) {
        clear();
        title.setVisible(false);
        date.setVisible(false);
        empty.setText(message);
        empty.setVisible(!message.isEmpty());
        empty.setBounds(20, 120, 600, 40);
        paper.setBackground(Gfx.color(PageColor.NONE.rgb));
        paper.redraw();
        updateSize();
    }

    private void clear() {
        for (ElementView v : views) v.dispose();
        views.clear();
        selected = null;
        lastText = null;
        activeTable = null;
        onTableActive.run();
    }

    /** Builds the page's containers from the model (also after Undo of a container change, and a zoom). */
    private void build() {
        loading = true;
        try {
            clear();
            empty.setVisible(false);
            Color bg = paperColor();
            paper.setBackground(bg);
            title.setVisible(true);
            title.setText(content.title);
            title.setFont(Gfx.font("Segoe UI Light", 26, false, false, zoom));
            title.setBounds(px(MARGIN - 2), px(20), px(TITLE_WIDTH), px(48));
            date.setVisible(true);
            date.setForeground(Gfx.color(115, 115, 115));
            date.setFont(Gfx.font("Segoe UI", 12, false, false, zoom));
            showDates();
            for (PageElement e : content.elements) {
                ElementView v = makeView(e);
                if (v == null) continue;
                if (v instanceof TextBox t && t.isBlank() && e.rtfd != null && !RichCodec.decode(e.rtfd).hasAttachments()) {
                    v.dispose();
                    continue;
                }
                views.add(v);
                v.moveAbove(null);
            }
            title.moveBelow(null);
            date.moveBelow(null);
            updateSize();
            paper.redraw();
        } finally {
            loading = false;
        }
    }

    private void showDates() {
        if (content == null) return;
        PageMeta m = store.meta(ref);
        String created = headerDate(content.created);
        String text = created;
        String tip = "Created " + created.replace("      ", " at ");
        if (m != null) {
            String updated = headerDate(m.modified);
            text += "     │     Updated  " + updated;
            tip += "\nLast updated " + updated.replace("      ", " at ");
        }
        date.setText(text);
        date.setToolTipText(tip);
        Point size = date.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        date.setBounds(px(MARGIN + 2), px(74), size.x, size.y);
    }

    /** A date as the page header shows it: Thursday, October 15, 2026      9:12 PM */
    static String headerDate(java.time.Instant t) {
        LocalDateTime d = LocalDateTime.ofInstant(t, ZoneId.systemDefault());
        return d.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())) + "      "
                + d.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT));
    }

    ElementView makeView(PageElement e) {
        return switch (e.kind) {
            case PageElement.TEXT -> new TextBox(this, e);
            case PageElement.IMAGE -> new AssetBoxes.PictureBox(this, e);
            case PageElement.FILE, PageElement.AUDIO -> new AssetBoxes.FileBox(this, e);
            case PageElement.TABLE -> e.table != null ? new TableBox(this, e) : new AssetBoxes.OtherBox(this, e);
            default -> new AssetBoxes.OtherBox(this, e);
        };
    }

    void markDirty() {
        if (ref == null || content == null || loading) return;
        dirty = true;
        int gen = ++saveGeneration;
        root.getDisplay().timerExec(800, () -> {
            if (gen == saveGeneration && !root.isDisposed()) saveNow();
        });
    }

    /** Saves pending changes now (before the selection moves on, and when the window closes). */
    public void flush() {
        if (dirty) saveNow();
    }

    private void saveNow() {
        if (!dirty || ref == null || content == null) return;
        dirty = false;
        extractInlinePictures();
        content.title = title.getText();
        content.elements = snapshotElements();
        List<String> texts = new ArrayList<>();
        for (ElementView v : views) {
            String t = v.indexText();
            if (t != null) texts.add(t);
        }
        store.savePage(content, ref, PageIndexer.index(texts));
        showDates();
    }

    /** The containers to keep (a text box with nothing typed in it isn't one), back to front. */
    List<PageElement> snapshotElements() {
        List<PageElement> out = new ArrayList<>();
        for (ElementView v : orderedViews()) {
            if (v instanceof TextBox t && t.isBlank() && !t.text.getRichText().hasAttachments()) continue;
            out.add(v.snapshot().copy());
        }
        return out;
    }

    /** The views back to front (the paper's children are listed front first). */
    List<ElementView> orderedViews() {
        List<ElementView> out = new ArrayList<>();
        Control[] children = paper.getChildren();
        for (int i = children.length - 1; i >= 0; i--) if (children[i] instanceof ElementView v && views.contains(v)) out.add(v);
        return out;
    }

    /**
     * Pictures inside an edited text box can't be written back into its text (the Mac's RTFD package), so they
     * become pictures of their own on the page, under the text box.
     */
    private void extractInlinePictures() {
        for (ElementView v : new ArrayList<>(views)) {
            if (!(v instanceof TextBox t) || !t.text.edited) continue;
            RText rt = t.text.getRichText();
            if (!rt.hasAttachments()) continue;
            double y = t.element.y + t.element.height + 14;
            StringBuilder text = new StringBuilder();
            List<RText.Span> spans = new ArrayList<>();
            int removed = 0;
            for (RText.Span s : rt.spans) {
                int a = s.start() - removed;
                StringBuilder piece = new StringBuilder();
                for (int i = s.start(); i < s.end(); i++) {
                    char c = rt.text.charAt(i);
                    if (c == RText.ATTACHMENT) {
                        CharStyle.Attachment att = s.style().attachment();
                        if (att != null && att.data() != null) {
                            String ext = att.fileName().contains(".") ? att.fileName().substring(att.fileName().lastIndexOf('.') + 1) : "png";
                            String asset = store.writeAsset(att.data(), ext, ref);
                            PageElement pic = new PageElement(PageElement.IMAGE, t.element.x, y, att.width() > 0 ? att.width() : 320);
                            pic.asset = asset;
                            pic.fileName = att.fileName();
                            ElementView pv = makeView(pic);
                            views.add(pv);
                            pv.moveAbove(null);
                            y += pic.height + 14;
                        }
                        removed++;
                    } else {
                        piece.append(c);
                    }
                }
                text.append(piece);
                if (piece.length() > 0) spans.add(new RText.Span(a, a + piece.length(), s.style().withAttachment(null)));
            }
            Point sel = t.text.getSelection();
            t.text.setRichText(new RText(text.toString(), spans, rt.paras));
            t.text.edited = true;
            t.text.setSelection(Math.min(sel.x, t.text.getCharCount()));
            t.fit();
        }
    }

    public PageContent currentContent() {
        if (content == null) return null;
        PageContent c = content.copy();
        c.title = title.getText();
        c.elements = snapshotElements();
        return c;
    }

    // MARK: Snapshots for Undo

    /** The page's containers and style, for undoing a change to them. */
    public record Snapshot(JsonArray elements, PageColor color, RuleLines rules) {}

    Snapshot snapshot() {
        JsonArray a = new JsonArray();
        for (PageElement e : snapshotElements()) a.add(e.toJson());
        return new Snapshot(a, content == null ? PageColor.NONE : content.color, content == null ? RuleLines.NONE : content.rules);
    }

    private void restore(Snapshot s) {
        if (content == null) return;
        content.elements = new ArrayList<>();
        for (JsonElement e : s.elements) content.elements.add(PageElement.fromJson(e.getAsJsonObject()));
        content.color = s.color;
        content.rules = s.rules;
        Point origin = scroll.getOrigin();
        build();
        scroll.setOrigin(origin);
        markDirty();
    }

    /** Records a change to the page's containers: {@code before} is how they were. */
    void pushUndo(String name, Snapshot before) {
        if (before == null) return;
        Snapshot[] after = new Snapshot[1];
        undo.push(name, () -> {
            after[0] = snapshot();
            restore(before);
        }, () -> {
            if (after[0] != null) restore(after[0]);
        });
        typingElement = null;
    }

    /** A text box is about to change: one Undo step per burst of typing, one per command. */
    void textWillChange(TextBox box, String action, boolean typing) {
        long now = System.currentTimeMillis();
        String id = box.element.id;
        if (typing && id.equals(typingElement) && now - typingTime < 2000 && undo.lastName().equals("Typing")) {
            typingTime = now;
            return;
        }
        NoteText.Snapshot before = box.text.snapshot();
        NoteText.Snapshot[] after = new NoteText.Snapshot[1];
        undo.push(typing ? "Typing" : action, () -> {
            TextBox t = textBox(id);
            if (t == null) return;
            after[0] = t.text.snapshot();
            t.text.restore(before);
            t.text.setFocus();
        }, () -> {
            TextBox t = textBox(id);
            if (t != null && after[0] != null) {
                t.text.restore(after[0]);
                t.text.setFocus();
            }
        });
        typingElement = typing ? id : null;
        typingTime = now;
    }

    /** A table is about to change: {@code before} is its data as it was. */
    void tableWillChange(TableBox box, String action, TableData before, double widthBefore) {
        String id = box.element.id;
        TableData[] after = new TableData[1];
        double[] afterWidth = new double[1];
        undo.push(action, () -> {
            TableBox t = tableBox(id);
            if (t == null) return;
            after[0] = t.data().copy();
            afterWidth[0] = t.element.width;
            t.restore(before, widthBefore);
        }, () -> {
            TableBox t = tableBox(id);
            if (t != null && after[0] != null) t.restore(after[0], afterWidth[0]);
        });
        typingElement = null;
    }

    TextBox textBox(String id) {
        for (ElementView v : views) if (v instanceof TextBox t && t.element.id.equals(id)) return t;
        return null;
    }

    TableBox tableBox(String id) {
        for (ElementView v : views) if (v instanceof TableBox t && t.element.id.equals(id)) return t;
        return null;
    }

    public void undo() {
        flushTypingGroup();
        undo.undo();
    }

    public void redo() {
        undo.redo();
    }

    private void flushTypingGroup() { typingElement = null; }

    // MARK: Containers

    void contentChanged() {
        updateSize();
        markDirty();
    }

    void contentResized() { updateSize(); }

    /** Adds a container on top (undoable as "Insert"). */
    ElementView add(PageElement e, boolean undoable) {
        if (ref == null || content == null) return null;
        Snapshot before = undoable ? snapshot() : null;
        ElementView v = makeView(e);
        views.add(v);
        v.moveAbove(null);
        if (undoable) pushUndo("Insert", before);
        contentChanged();
        return v;
    }

    /** Starts a new text container at a point on the page (in points). */
    TextBox createText(double x, double y, RText text, double width) {
        PageElement e = new PageElement(PageElement.TEXT, Math.max(4, snapX(x - 8)), Math.max(4, snapY(y - ElementView.GRIP - 10)), width);
        if (text != null) e.rtfd = RichCodec.encode(text);
        TextBox t = (TextBox) add(e, text != null);
        if (t != null) {
            t.text.setTyping(CharStyle.DEFAULT);
            t.focus(true);
        }
        return t;
    }

    void remove(ElementView v, boolean undoable) {
        if (!views.contains(v)) return;
        Snapshot before = undoable ? snapshot() : null;
        if (selected == v) selected = null;
        if (lastText == v) lastText = null;
        if (activeTable == v) {
            activeTable = null;
            onTableActive.run();
        }
        views.remove(v);
        // It may be handling the key or click that removed it: it goes once that's done.
        v.setVisible(false);
        root.getDisplay().asyncExec(() -> {
            if (!v.isDisposed()) v.dispose();
        });
        if (undoable) pushUndo("Delete", before);
        contentChanged();
    }

    void bringToFront(ElementView v) {
        Snapshot before = snapshot();
        v.moveAbove(null);
        pushUndo("Bring to Front", before);
        markDirty();
    }

    void sendToBack(ElementView v) {
        Snapshot before = snapshot();
        Control bottom = null;
        for (ElementView o : views) if (o != v) bottom = o;
        List<ElementView> ordered = orderedViews();
        if (!ordered.isEmpty() && ordered.get(0) != v) v.moveBelow(ordered.get(0));
        pushUndo("Send to Back", before);
        markDirty();
    }

    void select(ElementView v) {
        ElementView old = selected;
        selected = v;
        if (old != null && old != v && !old.isDisposed()) {
            old.selected = false;
            old.setFramed();
        }
        if (v != null) {
            v.selected = true;
            v.setFramed();
            paper.forceFocus();
        }
        onFormatting.run();
    }

    /** A container grew or shrank: containers sitting just under it (and overlapping it sideways) move with it. */
    void grew(ElementView view, double oldBottom) {
        double delta = view.element.y + view.element.height - oldBottom;
        if (Math.abs(delta) > 0.5) {
            List<ElementView> moved = new ArrayList<>();
            moved.add(view);
            List<Object[]> queue = new ArrayList<>();
            queue.add(new Object[] { view, oldBottom });
            while (!queue.isEmpty()) {
                Object[] q = queue.remove(0);
                ElementView src = (ElementView) q[0];
                double bottom = (double) q[1];
                for (ElementView o : views) {
                    if (moved.contains(o)) continue;
                    PageElement f = o.element;
                    if (f.y < bottom - 1 || f.y > bottom + 24) continue;
                    if (f.x + f.width <= src.element.x || f.x >= src.element.x + src.element.width) continue;
                    moved.add(o);
                    double ob = f.y + f.height;
                    f.y = Math.max(0, f.y + delta);
                    o.place();
                    queue.add(new Object[] { o, ob });
                }
            }
        }
        updateSize();
    }

    double gridStep() { return content != null && content.rules.spacing > 0 ? content.rules.spacing : 12; }

    private boolean snaps() { return snapToGrid; }

    double snapX(double x) { return snaps() ? Math.round(x / gridStep()) * gridStep() : x; }

    double snapY(double y) {
        if (!snaps()) return y;
        double origin = content != null && content.rules.spacing > 0 && !content.rules.isGrid() ? CONTENT_TOP : 0;
        return Math.round((y - origin) / gridStep()) * gridStep() + origin;
    }

    // MARK: Size and drawing

    void updateSize() {
        if (paper.isDisposed()) return;
        double maxX = MARGIN + TITLE_WIDTH, maxY = CONTENT_TOP;
        for (ElementView v : views) {
            maxX = Math.max(maxX, v.element.x + v.element.width);
            maxY = Math.max(maxY, v.element.y + v.element.height);
        }
        if (content != null) {
            double[] ink = content.inkBounds();
            if (ink != null) {
                maxX = Math.max(maxX, ink[2]);
                maxY = Math.max(maxY, ink[3]);
            }
        }
        Rectangle client = scroll.getClientArea();
        int w = Math.max(Math.max(px(maxX + 320), client.width), px(900));
        int h = Math.max(Math.max(px(maxY + 480), client.height), px(1000));
        Point size = paper.getSize();
        if (size.x != w || size.y != h) paper.setSize(w, h);
        updatePaper();
    }

    private void paint(GC gc, int x, int y, int w, int h) {
        if (content == null) return;
        paintInk(gc);
    }

    private Image paperImage;
    private String paperKey = "";

    /**
     * The paper — its colour, rule lines or grid, and the line under the title — as the page's background picture,
     * which the containers on it show through (the page is always drawn as light paper, as on the Mac).
     */
    private void updatePaper() {
        Point size = paper.getSize();
        String key = size.x + "x" + size.y + "|" + (content == null ? "" : content.color + "|" + content.rules) + "|" + zoom;
        if (key.equals(paperKey) && paperImage != null) return;
        paperKey = key;
        Image old = paperImage;
        paperImage = new Image(root.getDisplay(), Math.max(1, size.x), Math.max(1, size.y));
        GC gc = new GC(paperImage);
        try {
            gc.setBackground(paperColor());
            gc.fillRectangle(0, 0, size.x, size.y);
            if (content != null) {
                RuleLines rules = content.rules;
                if (rules.spacing > 0) {
                    double spacing = rules.spacing * zoom;
                    int firstY = rules.isGrid() ? 0 : px(CONTENT_TOP);
                    gc.setForeground(Gfx.color(rules.isGrid() ? new RGB(201, 214, 233) : new RGB(176, 198, 228)));
                    for (double ly = firstY; ly <= size.y; ly += spacing) gc.drawLine(0, (int) ly, size.x, (int) ly);
                    if (rules.isGrid()) {
                        for (double lx = 0; lx <= size.x; lx += spacing) gc.drawLine((int) lx, 0, (int) lx, size.y);
                    } else {
                        gc.setForeground(Gfx.color(232, 140, 140));
                        int mx = px(36.5);
                        gc.drawLine(mx, px(CONTENT_TOP - 10), mx, size.y);
                    }
                }
                gc.setForeground(Gfx.color(184, 184, 184));
                gc.drawLine(px(MARGIN), px(69.5), px(MARGIN + TITLE_WIDTH), px(69.5));
            }
        } finally {
            gc.dispose();
        }
        paper.setBackgroundImage(paperImage);
        if (old != null) old.dispose();
    }

    /** The page's ink (pen strokes and shapes drawn on the Mac), shown as it was drawn. */
    private void paintInk(GC gc) {
        if (!content.hasInk()) return;
        gc.setAntialias(SWT.ON);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setLineJoin(SWT.JOIN_ROUND);
        for (JsonElement s : content.strokes()) {
            if (!s.isJsonObject()) continue;
            var o = s.getAsJsonObject();
            var color = SwiftJson.obj(o, "color");
            RGB rgb = color == null ? Gfx.BLACK : Gfx.rgb(new double[] { SwiftJson.num(color, "r", 0), SwiftJson.num(color, "g", 0), SwiftJson.num(color, "b", 0) });
            String tool = SwiftJson.str(o, "tool", "pen");
            gc.setForeground(Gfx.color(rgb));
            gc.setAlpha("highlighter".equals(tool) ? 90 : 255);
            gc.setLineWidth(Math.max(1, px(SwiftJson.num(o, "width", 2))));
            JsonArray pts = SwiftJson.arr(o, "points");
            if (pts == null || pts.isEmpty()) continue;
            int[] xy = new int[pts.size() * 2];
            for (int i = 0; i < pts.size(); i++) {
                JsonArray p = pts.get(i).getAsJsonArray();
                xy[2 * i] = px(p.get(0).getAsDouble());
                xy[2 * i + 1] = px(p.get(1).getAsDouble());
            }
            switch (tool) {
                case "rectangle" -> gc.drawRectangle(Math.min(xy[0], xy[xy.length - 2]), Math.min(xy[1], xy[xy.length - 1]),
                        Math.abs(xy[xy.length - 2] - xy[0]), Math.abs(xy[xy.length - 1] - xy[1]));
                case "ellipse" -> gc.drawOval(Math.min(xy[0], xy[xy.length - 2]), Math.min(xy[1], xy[xy.length - 1]),
                        Math.abs(xy[xy.length - 2] - xy[0]), Math.abs(xy[xy.length - 1] - xy[1]));
                default -> gc.drawPolyline(xy);
            }
            gc.setAlpha(255);
        }
    }

    private void trackHover() {
        if (paper.isDisposed() || views.isEmpty()) return;
        Point cursor = paper.toControl(root.getDisplay().getCursorLocation());
        boolean inside = paper.getClientArea().contains(cursor) && root.getDisplay().getCursorControl() != null
                && isInside(root.getDisplay().getCursorControl(), paper);
        ElementView top = null;
        if (inside) {
            for (Control c : paper.getChildren()) {
                if (c instanceof ElementView v && c.getBounds().contains(cursor)) {
                    top = v;
                    break;
                }
            }
        }
        for (ElementView v : views) {
            boolean h = v == top;
            if (v.hover != h) {
                v.hover = h;
                v.setFramed();
            }
        }
    }

    static boolean isInside(Control c, Control ancestor) {
        for (Control at = c; at != null; at = at.getParent()) if (at == ancestor) return true;
        return false;
    }

    private void wheel(Event e) {
        if (!(e.widget instanceof Control c) || !isInside(c, paper) && c != scroll) return;
        if ((e.stateMask & SWT.CTRL) != 0) {
            e.doit = false;
            if (e.count > 0) zoomIn(); else zoomOut();
            return;
        }
        if (c == scroll || c == paper) return;
        e.doit = false;
        Point o = scroll.getOrigin();
        scroll.setOrigin(o.x, Math.max(0, o.y - e.count * 16));
    }

    // MARK: Mouse and keys on the page

    private void paperMouseDown(Event e) {
        if (ref == null || content == null) return;
        select(null);
        paper.forceFocus();
        if (e.button != 1) return;
        String mode = store.prefs().get("newTextBoxOnClick", "click");
        boolean starts = "click".equals(mode) || "doubleClick".equals(mode) && e.count >= 2;
        double x = e.x / zoom, y = e.y / zoom;
        if (starts && y > CONTENT_TOP - 24) createText(x, y, null, 520);
    }

    private void paperKey(Event e) {
        if (selected == null) {
            if (e.keyCode == SWT.ESC) select(null);
            return;
        }
        double step = snapToGrid ? gridStep() : (e.stateMask & SWT.SHIFT) != 0 ? 10 : 1;
        double dx = 0, dy = 0;
        switch (e.keyCode) {
            case SWT.DEL, SWT.BS -> {
                remove(selected, true);
                return;
            }
            case SWT.CR, SWT.KEYPAD_CR -> {
                if (selected instanceof TextBox t) t.focus(true);
                else if (selected instanceof TableBox t) t.beginEditing(0, 0);
                return;
            }
            case SWT.ESC -> {
                select(null);
                return;
            }
            case SWT.ARROW_LEFT -> dx = -step;
            case SWT.ARROW_RIGHT -> dx = step;
            case SWT.ARROW_UP -> dy = -step;
            case SWT.ARROW_DOWN -> dy = step;
            default -> {
                return;
            }
        }
        Snapshot before = snapshot();
        selected.element.x = Math.max(0, selected.element.x + dx);
        selected.element.y = Math.max(0, selected.element.y + dy);
        selected.place();
        pushUndo("Move", before);
        contentChanged();
    }

    private void fillPaperMenu(Menu menu) {
        for (MenuItem mi : menu.getItems()) mi.dispose();
        if (ref == null || content == null) return;
        Point p = paper.toControl(root.getDisplay().getCursorLocation());
        double[] at = { p.x / zoom, p.y / zoom };
        ElementView.item(menu, "New Text Box Here", () -> createText(at[0], at[1], null, 520));
        ElementView.item(menu, "Paste Here", () -> paste(at));
        MenuItem insert = new MenuItem(menu, SWT.CASCADE);
        insert.setText("Insert Here");
        Menu im = new Menu(insert);
        insert.setMenu(im);
        ElementView.item(im, "Table", () -> insertAt(at, () -> insertTable(3, 3)));
        ElementView.item(im, "Picture…", () -> insertAt(at, this::choosePictures));
        ElementView.item(im, "File Attachment…", () -> insertAt(at, this::chooseAttachments));
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem clicks = new MenuItem(menu, SWT.CASCADE);
        clicks.setText("Clicking the Page");
        Menu cm = new Menu(clicks);
        clicks.setMenu(cm);
        String mode = store.prefs().get("newTextBoxOnClick", "click");
        String[][] options = { { "click", "Click Starts a New Text Box" }, { "doubleClick", "Double-Click Starts a New Text Box" },
                { "menu", "Clicking Does Nothing" } };
        for (String[] o : options) {
            MenuItem mi = new MenuItem(cm, SWT.RADIO);
            mi.setText(o[1]);
            mi.setSelection(o[0].equals(mode));
            mi.addListener(SWT.Selection, e -> {
                if (mi.getSelection()) store.prefs().put("newTextBoxOnClick", o[0]);
            });
        }
    }

    /** Right-click in a text box: Copy Link to this Paragraph and Page, link commands, then the box's own. */
    void fillTextMenu(Menu menu, TextBox box) {
        NoteText t = box.text;
        ElementView.item(menu, "Cut", t::cut).setEnabled(t.getSelectionCount() > 0);
        ElementView.item(menu, "Copy", t::copy).setEnabled(t.getSelectionCount() > 0);
        ElementView.item(menu, "Paste", t::paste);
        new MenuItem(menu, SWT.SEPARATOR);
        int[] link = t.linkRange(t.getCaretOffset());
        if (link != null) {
            String url = t.styleAt(link[0]).link();
            ElementView.item(menu, "Open Link", () -> openLink(url));
            ElementView.item(menu, "Edit Link…", () -> Dialogs.link(this, box.text));
            ElementView.item(menu, "Copy Link Address", () -> com.k3jdr.mx.core.ui.Clipboards.copyText(root.getDisplay(), url));
            ElementView.item(menu, "Remove Link", t::removeLink);
            new MenuItem(menu, SWT.SEPARATOR);
        }
        ElementView.item(menu, "Copy Link to this Paragraph", () -> {
            int[] para = t.paragraphAt(t.getCaretOffset());
            String text = t.getText().substring(para[0], para[1]);
            int index = 0;
            for (int i = 0; i < para[0]; i++) if (t.getText().charAt(i) == '\n') index++;
            copyParagraphLink(box.element.id, index, text);
        });
        ElementView.item(menu, "Copy Link to this Page", () -> com.k3jdr.mx.core.ui.Clipboards.copyText(root.getDisplay(), NotebookStore.link(ref)));
        new MenuItem(menu, SWT.SEPARATOR);
        box.fillMenu(menu);
    }

    private void copyParagraphLink(String elementId, int paragraph, String text) {
        String words = text.strip();
        StringBuilder url = new StringBuilder(NotebookStore.link(ref)).append("?e=").append(elementId).append("&p=").append(paragraph);
        if (!words.isEmpty()) {
            url.append("&t=").append(java.net.URLEncoder.encode(words.length() > 60 ? words.substring(0, 60) : words,
                    java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        }
        com.k3jdr.mx.core.ui.Clipboards.copyText(root.getDisplay(), url.toString());
    }

    // MARK: Focus and formatting targets

    void textFocused(TextBox box) {
        lastText = box;
        if (selected != null) select(null);
        if (activeTable != null) {
            activeTable = null;
            onTableActive.run();
        }
        trackHover();
        onFormatting.run();
        box.setFramed();
    }

    /** A text box left with nothing in it goes away when the typing moves elsewhere (TextElementView). */
    void blankCheck(TextBox box) {
        root.getDisplay().asyncExec(() -> {
            if (box.isDisposed() || box.text.isFocusControl() || selected == box || !box.isBlank()) {
                if (!box.isDisposed()) box.setFramed();
                return;
            }
            remove(box, false);
        });
    }

    void tableFocused(TableBox box) {
        if (activeTable != box) {
            activeTable = box;
            onTableActive.run();
        }
        if (selected != null && selected != box) select(null);
        onFormatting.run();
    }

    void formattingChanged() { onFormatting.run(); }

    /** The text being edited (or the last one edited, when the focus went to the ribbon). */
    public NoteText activeText() {
        Control focus = root.getDisplay().getFocusControl();
        if (focus instanceof NoteText t && isInside(t, paper)) return t;
        if (activeTable != null && activeTable.editor() != null && !activeTable.editor().isDisposed()) return activeTable.editor();
        if (lastText != null && !lastText.isDisposed() && !(focus != null && isInside(focus, paper) && focus != lastText.text)) return lastText.text;
        return null;
    }

    /** The table whose cells are selected (not being typed in), for formatting them all at once. */
    public TableBox cellTarget() {
        return activeTable != null && !activeTable.isDisposed() && activeTable.editor() == null && activeTable.hasSelection() ? activeTable : null;
    }

    public TableBox activeTable() { return activeTable != null && !activeTable.isDisposed() ? activeTable : null; }

    /** Arrow keys on the first / last line go on into the container right above / below. */
    boolean moveFocus(ElementView from, boolean up, double x) {
        ElementView best = null;
        double bestDist = Double.MAX_VALUE;
        for (ElementView v : views) {
            if (v == from || !(v instanceof TextBox || v instanceof TableBox)) continue;
            PageElement f = v.element;
            if (f.x + f.width < from.element.x - 40 || f.x > from.element.x + from.element.width + 40) continue;
            double d = up ? from.element.y - (f.y + f.height) : f.y - (from.element.y + from.element.height);
            if (d < -4 || d > 400) continue;
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        if (best instanceof TextBox t) {
            t.focus(up, x);
            return true;
        }
        if (best instanceof TableBox t) {
            t.beginEditing(up ? t.data().rowCount() - 1 : 0, 0);
            return true;
        }
        return false;
    }

    void focusFirstText() {
        TextBox first = null;
        for (ElementView v : views) {
            if (v instanceof TextBox t && (first == null || t.element.y < first.element.y
                    || t.element.y == first.element.y && t.element.x < first.element.x)) first = t;
        }
        if (first != null) first.focus(false);
        else createText(MARGIN + 8, CONTENT_TOP + 20, null, 520);
    }

    // MARK: Inserting

    /** Where inserted things go: where the page was right-clicked, under the container being edited, or near the top. */
    double[] nextInsertionPoint() {
        if (insertionPoint != null) return new double[] { insertionPoint.x, insertionPoint.y };
        ElementView anchor = lastText != null && !lastText.isDisposed() && lastText.text.isFocusControl() ? lastText : selected;
        if (anchor != null) return new double[] { anchor.element.x, anchor.element.y + anchor.element.height + 14 };
        Point o = scroll.getOrigin();
        return new double[] { Math.max(MARGIN, o.x / zoom + MARGIN), Math.max(CONTENT_TOP, o.y / zoom + 60) };
    }

    private void insertAt(double[] at, Runnable command) {
        insertionPoint = new Point((int) at[0], (int) at[1]);
        try {
            command.run();
        } finally {
            insertionPoint = null;
        }
    }

    public void insertTable(int rows, int columns) {
        if (ref == null) return;
        double[] at = nextInsertionPoint();
        PageElement e = new PageElement(PageElement.TABLE, snapX(at[0]), snapY(at[1]), 480);
        e.table = new TableData(rows, columns, 480);
        TableBox t = (TableBox) add(e, true);
        if (t != null) t.beginEditing(0, 0);
    }

    public void choosePictures() {
        if (ref == null) return;
        FileDialog fd = new FileDialog(root.getShell(), SWT.OPEN | SWT.MULTI);
        fd.setText("Insert Picture");
        fd.setFilterExtensions(new String[] { "*.png;*.jpg;*.jpeg;*.gif;*.bmp;*.tif;*.tiff", "*.*" });
        fd.setFilterNames(new String[] { "Pictures", "All Files" });
        if (fd.open() == null) return;
        String[] names = fd.getFileNames();
        String[] files = new String[names.length];
        for (int i = 0; i < names.length; i++) files[i] = Path.of(fd.getFilterPath(), names[i]).toString();
        insertFiles(files, nextInsertionPoint());
    }

    public void chooseAttachments() {
        if (ref == null) return;
        FileDialog fd = new FileDialog(root.getShell(), SWT.OPEN | SWT.MULTI);
        fd.setText("Attach File");
        if (fd.open() == null) return;
        String[] names = fd.getFileNames();
        double[] at = nextInsertionPoint();
        for (String n : names) attachFile(Path.of(fd.getFilterPath(), n), at, true);
    }

    static boolean isPicture(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp")
                || n.endsWith(".tif") || n.endsWith(".tiff");
    }

    /** Dropped or chosen files: pictures go on the page, other files as attachments (a copy kept in the notebook). */
    void insertFiles(String[] files, double[] at) {
        double y = at[1];
        for (String f : files) {
            Path p = Path.of(f);
            if (Files.isDirectory(p)) continue;
            if (isPicture(f)) insertPicture(p, new double[] { at[0], y });
            else attachFile(p, new double[] { at[0], y }, false);
            y += 60;
        }
    }

    private void insertPicture(Path file, double[] at) {
        PageRef target = ref;
        Background.run(root, () -> Files.readAllBytes(file), data -> {
            if (!target.equals(ref)) return;
            int width = 320;
            try {
                ImageData img = new ImageData(new java.io.ByteArrayInputStream(data));
                width = Math.min(img.width, 600);
            } catch (RuntimeException e) {
                ErrorDialog.show(root.getShell(), "Can't insert the picture", file.getFileName() + " isn't a picture this app can show.");
                return;
            }
            String ext = file.getFileName().toString();
            ext = ext.contains(".") ? ext.substring(ext.lastIndexOf('.') + 1) : "png";
            PageElement e = new PageElement(PageElement.IMAGE, snapX(at[0]), snapY(at[1]), width);
            e.asset = store.writeAsset(data, ext, ref);
            add(e, true);
        });
    }

    private void insertImageData(ImageData img, double[] at) {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { img };
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        loader.save(out, SWT.IMAGE_PNG);
        PageElement e = new PageElement(PageElement.IMAGE, snapX(at[0]), snapY(at[1]), Math.min(img.width, 600));
        e.asset = store.writeAsset(out.toByteArray(), "png", ref);
        add(e, true);
    }

    private void attachFile(Path file, double[] at, boolean undoable) {
        PageRef target = ref;
        Background.run(root, () -> Files.readAllBytes(file), data -> {
            if (!target.equals(ref)) return;
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            PageElement e = new PageElement(PageElement.FILE, snapX(at[0]), snapY(at[1]), 260);
            e.asset = store.writeAsset(data, ext, ref);
            e.fileName = name;
            add(e, true);
        });
    }

    /** Insert ▸ Date / Time: typed into the text being edited, or a new text box. */
    public void insertString(String s) {
        NoteText t = activeText();
        if (t != null) {
            t.setFocus();
            t.insertTyped(s);
            return;
        }
        if (ref == null) return;
        double[] at = nextInsertionPoint();
        TextBox box = createText(at[0] + 8, at[1] + ElementView.GRIP + 10, null, 520);
        if (box != null) box.text.insertTyped(s);
    }

    // MARK: OneNote-style tables from text

    /** Text followed by Tab turns the paragraph into a two-column table; the text after it moves below the table. */
    boolean convertParagraphToTable(TextBox box, int paraStart, int paraEnd) {
        NoteText t = box.text;
        String text = t.getText().substring(paraStart, paraEnd).replace('\r', ' ').strip();
        if (text.isEmpty()) return false;
        Snapshot before = snapshot();
        CharStyle font = t.styleAt(paraStart);
        GC gc = new GC(t);
        gc.setFont(Gfx.font(font, 1));
        double firstWidth = Math.min(Math.max(Math.ceil(gc.textExtent(text).x) + 28, 90), 360);
        gc.dispose();
        TableData table = new TableData(1, 2, firstWidth + 120);
        table.headerRow = false;
        table.rows.get(0).set(0, text);
        table.columnWidths = new ArrayList<>(List.of(firstWidth, 120.0));
        RText all = t.getRichText();
        RText trailing = paraEnd < all.length() ? all.slice(paraEnd + 1, all.length()) : null;
        double paragraphTop = box.element.y + ElementView.GRIP + t.getLocationAtOffset(paraStart).y / zoom;
        double x = box.element.x;
        double tableY;
        loading = true;
        try {
            if (paraStart == 0 && trailing == null) {
                tableY = box.element.y;
                remove(box, false);
            } else if (paraStart == 0) {
                tableY = box.element.y;
                remove(box, false);
            } else {
                t.setRichText(all.slice(0, paraStart - 1));
                t.edited = true;
                box.fit();
                tableY = paragraphTop - ElementView.GRIP + 2;
            }
        } finally {
            loading = false;
        }
        PageElement e = new PageElement(PageElement.TABLE, x, Math.max(0, tableY), firstWidth + 120);
        e.table = table;
        ElementView tv = makeView(e);
        views.add(tv);
        tv.moveAbove(null);
        if (trailing != null && !trailing.isBlank()) {
            PageElement rest = new PageElement(PageElement.TEXT, x, tv.element.y + tv.element.height + 14, box.element.width);
            rest.rtfd = RichCodec.encode(trailing);
            ElementView rv = makeView(rest);
            views.add(rv);
            rv.moveAbove(null);
        }
        pushUndo("Table", before);
        contentChanged();
        ((TableBox) tv).beginEditing(0, 1);
        return true;
    }

    // MARK: Clipboard for containers

    private static List<PageElement> clip = List.of();
    private static List<byte[]> clipAssets = List.of();
    private static String clipMarker;

    void copy(ElementView v) {
        PageElement e = v.snapshot().copy();
        String text = v.indexText();
        clipMarker = "MxNoteONE container " + Kinds.newId();
        String plain = text != null && !text.isBlank() ? text : e.kindTitle();
        PageRef from = ref;
        List<byte[]> assets = new ArrayList<>();
        clip = List.of(e);
        clipAssets = assets;
        if (e.asset != null) {
            Disk.submit(() -> {
                byte[] d = store.assetData(e.asset, from);
                synchronized (assets) {
                    assets.add(d);
                }
            });
        }
        Clipboard cb = new Clipboard(root.getDisplay());
        try {
            cb.setContents(new Object[] { plain + "​" }, new Transfer[] { TextTransfer.getInstance() });
            clipMarker = plain + "​";
        } finally {
            cb.dispose();
        }
    }

    void cut(ElementView v) {
        copy(v);
        remove(v, true);
    }

    /** Paste on the page: copied containers, a picture, or text as a new text box. */
    public void paste(double[] at) {
        if (ref == null) return;
        Clipboard cb = new Clipboard(root.getDisplay());
        try {
            Object text = cb.getContents(TextTransfer.getInstance());
            if (text instanceof String s && s.equals(clipMarker) && !clip.isEmpty()) {
                PageElement e = clip.get(0).duplicate();
                double[] p = at != null ? at : new double[] { e.x + 20, e.y + 20 };
                e.x = p[0];
                e.y = p[1];
                if (e.asset != null) {
                    byte[] data;
                    synchronized (clipAssets) {
                        data = clipAssets.isEmpty() ? null : clipAssets.get(0);
                    }
                    if (data != null) e.asset = store.writeAsset(data, e.asset.contains(".") ? e.asset.substring(e.asset.lastIndexOf('.') + 1) : "", ref);
                }
                ElementView v = add(e, true);
                select(v);
                return;
            }
            Object img = cb.getContents(ImageTransfer.getInstance());
            if (img instanceof ImageData data) {
                insertImageData(data, at != null ? at : nextInsertionPoint());
                return;
            }
            if (text instanceof String s && !s.isEmpty()) {
                if (TableBox.looksLikeTable(s)) {
                    double[] p = at != null ? at : nextInsertionPoint();
                    PageElement e = new PageElement(PageElement.TABLE, p[0], p[1], 480);
                    e.table = TableBox.tableFromTsv(s);
                    add(e, true);
                    return;
                }
                double[] p = at != null ? at : nextInsertionPoint();
                TextBox box = createText(p[0] + 8, p[1] + ElementView.GRIP + 10, null, 520);
                if (box != null) box.text.paste();
            }
        } finally {
            cb.dispose();
        }
    }

    void copyPicture(Image image) {
        if (image == null) return;
        Clipboard cb = new Clipboard(root.getDisplay());
        try {
            cb.setContents(new Object[] { image.getImageData() }, new Transfer[] { ImageTransfer.getInstance() });
        } finally {
            cb.dispose();
        }
    }

    void openAsset(PageElement e) {
        if (e.asset == null || ref == null) return;
        PageRef r = ref;
        String name = e.fileName != null ? e.fileName : PageElement.IMAGE.equals(e.kind) ? "Picture." + extension(e.asset) : e.asset;
        Disk.submit(() -> {
            Path tmp = store.temporaryCopy(e.asset, name, r);
            root.getDisplay().asyncExec(() -> {
                if (tmp == null) ErrorDialog.show(root.getShell(), "Can't open the file", "The attachment is missing from the notebook.");
                else Program.launch(tmp.toString());
            });
        });
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    void saveAssetAs(PageElement e) {
        if (e.asset == null || ref == null) return;
        FileDialog fd = new FileDialog(root.getShell(), SWT.SAVE);
        fd.setOverwrite(true);
        fd.setFileName(e.fileName != null ? e.fileName : "Picture." + extension(e.asset));
        String target = fd.open();
        if (target == null) return;
        PageRef r = ref;
        Disk.submit(() -> {
            byte[] data = store.assetData(e.asset, r);
            try {
                if (data == null) throw new IOException("The file is missing from the notebook.");
                Files.write(Path.of(target), data);
            } catch (IOException ex) {
                root.getDisplay().asyncExec(() -> ErrorDialog.show(root.getShell(), "Couldn't save the file.", ex));
            }
        });
    }

    // MARK: Links

    void openLink(String url) {
        if (url == null) return;
        if (url.toLowerCase(Locale.ROOT).startsWith(NotebookStore.LINK_SCHEME + "://")) {
            flush();
            PageRef target = store.openLink(url);
            if (target != null) revealParagraph(url);
            return;
        }
        Program.launch(url);
    }

    /** A paragraph link (…?e=&lt;container&gt;&amp;p=&lt;n&gt;&amp;t=&lt;words&gt;): select the paragraph once the page shows. */
    private void revealParagraph(String url) {
        int q = url.indexOf('?');
        if (q < 0) return;
        String element = null, words = null;
        int index = 0;
        for (String part : url.substring(q + 1).split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length < 2) continue;
            String v = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            switch (kv[0]) {
                case "e" -> element = v;
                case "p" -> index = Integer.parseInt(v.replaceAll("\\D", "0"));
                case "t" -> words = v;
                default -> {}
            }
        }
        String id = element, w = words;
        int p = index;
        root.getDisplay().timerExec(400, () -> {
            TextBox box = id == null ? null : textBox(id.toUpperCase(Locale.ROOT));
            if (box == null) return;
            String text = box.text.getText();
            int start = 0;
            if (w != null && !w.isBlank() && text.contains(w)) {
                start = text.lastIndexOf('\n', Math.max(0, text.indexOf(w) - 1)) + 1;
                if (text.indexOf(w) == 0) start = 0;
            } else {
                for (int i = 0, n = 0; i < text.length() && n < p; i++) if (text.charAt(i) == '\n') { n++; start = i + 1; }
            }
            int end = text.indexOf('\n', start);
            box.text.setFocus();
            box.text.setSelection(start, end < 0 ? text.length() : end);
            box.text.showSelection();
        });
    }

    String linkTitle(String url) {
        if (url.toLowerCase(Locale.ROOT).startsWith(NotebookStore.LINK_SCHEME + "://")) {
            PageRef r = store.resolve(url);
            PageMeta m = r == null ? null : store.meta(r);
            return m != null ? m.displayTitle() : "a page in your notebooks";
        }
        if (url.startsWith("mailto:")) return url.substring(7);
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null ? host : url;
        } catch (RuntimeException e) {
            return url;
        }
    }

    String linkDetail(String url) {
        if (url.toLowerCase(Locale.ROOT).startsWith(NotebookStore.LINK_SCHEME + "://")) {
            PageRef r = store.resolve(url);
            return r == null ? "The page may have been deleted, or its notebook isn't open." : store.location(r.section());
        }
        return url;
    }

    // MARK: Page style and zoom

    public void setPageColor(PageColor c) {
        if (content == null) return;
        Snapshot before = snapshot();
        content.color = c;
        Point origin = scroll.getOrigin();
        build();
        scroll.setOrigin(origin);
        pushUndo("Page Color", before);
        markDirty();
    }

    public void setRuleLines(RuleLines r) {
        if (content == null) return;
        Snapshot before = snapshot();
        content.rules = r;
        updatePaper();
        paper.redraw();
        pushUndo("Rule Lines", before);
        markDirty();
    }

    public void zoomTo(double z) {
        double nz = Math.max(0.25, Math.min(4, z));
        if (Math.abs(nz - zoom) < 0.001) return;
        if (content != null) {
            boolean wasDirty = dirty;
            content.title = title.getText();
            content.elements = snapshotElements();
            zoom = nz;
            Point o = scroll.getOrigin();
            build();
            scroll.setOrigin(o);
            dirty = wasDirty;
        } else {
            zoom = nz;
        }
    }

    public void zoomIn() { zoomTo(zoom * 1.25); }
    public void zoomOut() { zoomTo(zoom / 1.25); }

    public void zoomToPageWidth() {
        double maxX = MARGIN + TITLE_WIDTH;
        for (ElementView v : views) maxX = Math.max(maxX, v.element.x + v.element.width);
        zoomTo(scroll.getClientArea().width / (maxX + 40));
    }

    public PageRef ref() { return ref; }
    public PageContent content() { return content; }
    public Control control() { return root; }
    public boolean hasPage() { return content != null; }
    public ElementView selected() { return selected; }

    public void deleteSelected() {
        if (selected != null) remove(selected, true);
    }

    private final List<Runnable> whenLoaded = new ArrayList<>();

    /** Runs once the page shows (now, if it already does). */
    public void whenLoaded(PageRef target, Runnable r) {
        if (target.equals(ref) && content != null) r.run();
        else whenLoaded.add(() -> {
            if (target.equals(ref)) r.run();
        });
    }

    /** The page as it shows, without frames and selections (printing and PDF). */
    public Image capture() {
        if (content == null) return null;
        select(null);
        for (ElementView v : views) {
            v.hover = false;
            v.setFramed();
        }
        if (activeTable != null && !activeTable.isDisposed()) activeTable.endEditing();
        double maxX = MARGIN + TITLE_WIDTH, maxY = CONTENT_TOP;
        for (ElementView v : views) {
            maxX = Math.max(maxX, v.element.x + v.element.width);
            maxY = Math.max(maxY, v.element.y + v.element.height);
        }
        updatePaper();
        int w = Math.max(1, Math.min(paper.getSize().x, px(maxX + 40))), h = Math.max(1, Math.min(paper.getSize().y, px(maxY + 48)));
        Image img = new Image(root.getDisplay(), w, h);
        GC gc = new GC(img);
        org.eclipse.swt.graphics.Transform tr = new org.eclipse.swt.graphics.Transform(root.getDisplay());
        try {
            gc.setAntialias(SWT.ON);
            gc.setTextAntialias(SWT.ON);
            if (paperImage != null) gc.drawImage(paperImage, 0, 0);
            gc.setFont(title.getFont());
            gc.setForeground(Gfx.color(Gfx.BLACK));
            gc.drawText(title.getText(), px(MARGIN), px(24), true);
            gc.setFont(date.getFont());
            gc.setForeground(date.getForeground());
            gc.drawText(date.getText(), px(MARGIN + 2), px(74), true);
            for (ElementView v : orderedViews()) {
                Rectangle b = v.getBounds();
                Control c = v.content();
                if (c == null) continue;
                Rectangle cb = c.getBounds();
                tr.identity();
                tr.translate(b.x + cb.x, b.y + cb.y);
                gc.setTransform(tr);
                gc.setClipping(0, 0, cb.width, cb.height);
                if (c instanceof NoteText t) {
                    drawText(gc, t);
                } else {
                    Event e = new Event();
                    e.gc = gc;
                    e.x = 0;
                    e.y = 0;
                    e.width = cb.width;
                    e.height = cb.height;
                    c.notifyListeners(SWT.Paint, e);
                }
                gc.setClipping((Rectangle) null);
                gc.setTransform(null);
            }
            paintInk(gc);
        } finally {
            tr.dispose();
            gc.dispose();
        }
        return img;
    }

    /** A text box's text drawn line by line as the editor lays it out (its styles, indents and alignment). */
    private void drawText(GC gc, NoteText t) {
        Rectangle area = t.getClientArea();
        int y = t.getTopMargin();
        int width = area.width - t.getLeftMargin() - t.getRightMargin();
        for (int line = 0; line < t.getLineCount(); line++) {
            int start = t.getOffsetAtLine(line);
            String text = t.getLine(line);
            org.eclipse.swt.graphics.TextLayout l = new org.eclipse.swt.graphics.TextLayout(root.getDisplay());
            try {
                l.setText(text);
                l.setFont(t.getFont());
                l.setTabs(t.getTabStops());
                l.setWidth(Math.max(1, width));
                l.setIndent(t.getLineIndent(line));
                l.setWrapIndent(t.getLineWrapIndent(line));
                l.setAlignment(t.getLineAlignment(line));
                l.setJustify(t.getLineJustify(line));
                if (!text.isEmpty()) {
                    for (org.eclipse.swt.custom.StyleRange r : t.getStyleRanges(start, text.length(), true)) {
                        int a = Math.max(r.start, start) - start, z = Math.min(r.start + r.length, start + text.length()) - start;
                        if (z > a) l.setStyle(r, a, z - 1);
                    }
                }
                y += t.getLineVerticalIndent(line);
                l.draw(gc, t.getLeftMargin(), y);
                y += l.getBounds().height;
            } finally {
                l.dispose();
            }
        }
    }

    // MARK: Find on page

    private String findText = "";

    /** Edit ▸ Find on Page: selects the next match after the caret, in the containers top to bottom. */
    public boolean find(String query, boolean forward) {
        if (query == null || query.isEmpty() || content == null) return false;
        findText = query;
        List<TextBox> boxes = new ArrayList<>();
        for (ElementView v : views) if (v instanceof TextBox t) boxes.add(t);
        boxes.sort((a, b) -> a.element.y != b.element.y ? Double.compare(a.element.y, b.element.y) : Double.compare(a.element.x, b.element.x));
        if (boxes.isEmpty()) return false;
        String q = query.toLowerCase(Locale.ROOT);
        NoteText current = activeText();
        int startBox = 0, from = 0;
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).text == current) {
                startBox = i;
                Point sel = current.getSelection();
                from = forward ? sel.y : sel.x - 1;
            }
        }
        for (int k = 0; k <= boxes.size(); k++) {
            int i = forward ? (startBox + k) % boxes.size() : ((startBox - k) % boxes.size() + boxes.size()) % boxes.size();
            TextBox b = boxes.get(i);
            String text = b.text.getText().toLowerCase(Locale.ROOT);
            int at = forward ? text.indexOf(q, k == 0 ? Math.max(0, from) : 0) : text.lastIndexOf(q, k == 0 ? from : text.length());
            if (at >= 0) {
                b.text.setFocus();
                b.text.setSelection(at, at + q.length());
                b.text.showSelection();
                Rectangle bounds = b.getBounds();
                scroll.setOrigin(scroll.getOrigin().x, Math.max(0, bounds.y - 80));
                return true;
            }
        }
        return false;
    }

    public String lastFind() { return findText; }
}
