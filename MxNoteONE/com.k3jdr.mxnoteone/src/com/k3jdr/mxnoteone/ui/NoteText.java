package com.k3jdr.mxnoteone.ui;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.k3jdr.mxnoteone.model.Kinds.TagKind;
import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.ParaStyle;
import com.k3jdr.mxnoteone.text.Paragraphs;
import com.k3jdr.mxnoteone.text.Paragraphs.Info;
import com.k3jdr.mxnoteone.text.RText;

/**
 * A rich text editor for notes: a StyledText whose runs carry the Mac's formatting ({@link CharStyle} in each
 * StyleRange's data) and whose paragraphs carry {@link ParaStyle}s. It types and keys as the Mac's text containers
 * do (TextFormatter.swift): Return continues lists and To Dos, Backspace removes markers, Tab indents list items
 * (or, after text, asks for a table), bullets, numbering and tags are text at a paragraph's start with a hanging
 * indent as wide as the marker, and a click on ☐ checks it. "\n" ends a paragraph; inside the widget a line break
 * within a paragraph (U+2028, Shift+Enter) is a carriage return.
 */
public class NoteText extends StyledText {
    /** What the editor tells its container and the page. */
    public interface Host {
        default void willChange(NoteText t, String action, boolean typing) {}
        default void changed(NoteText t) {}
        default void escape(NoteText t) {}
        default boolean arrowOut(NoteText t, boolean up) { return false; }
        /** Tab after text in a plain paragraph: turn it into a table; true when done. */
        default boolean tabToTable(NoteText t, int paraStart, int paraEnd) { return false; }
        default void openLink(String url) {}
        default void focused(NoteText t) {}
        default void selectionChanged(NoteText t) {}
        /** [[Page Name]]: the link to the page (created when missing); null for none. */
        default String pageLink(String title) { return null; }
        /** A key before the editor's own handling (table cells: Tab, Return, arrows); true when it was used. */
        default boolean keyFirst(NoteText t, int keyCode, int stateMask, char character) { return false; }
    }

    private static final class Para {
        ParaStyle style;
        boolean hung;

        Para(ParaStyle s, boolean h) {
            style = s;
            hung = h;
        }
    }

    private final List<Para> paras = new ArrayList<>();
    private CharStyle typing = CharStyle.DEFAULT;
    private double zoom;
    private boolean internal;
    private Host host = new Host() {};
    private final Map<CharStyle.Attachment, Image> pictures = new HashMap<>();
    private boolean syncing;
    /** Set once the text was changed by the user (the stored rich text is then written anew). */
    public boolean edited;
    /** The style of what's typed into an empty editor (a table cell's font). */
    private CharStyle base = CharStyle.DEFAULT;
    /** The colour of text without a colour of its own (white on a dark table cell). */
    private RGB autoColor = Gfx.BLACK;

    public void setAutoColor(RGB c) { autoColor = c == null ? Gfx.BLACK : c; }

    public NoteText(Composite parent, int style, double zoom) {
        super(parent, style | SWT.MULTI | SWT.WRAP);
        this.zoom = zoom;
        paras.add(new Para(ParaStyle.DEFAULT, false));
        setKeyBinding(SWT.INSERT, SWT.NULL);
        setTabStops(new int[] { px(28) });
        setMargins(px(4), px(4), px(4), px(4));
        addVerifyListener(e -> {
            if (!internal) host.willChange(this, "Typing", true);
            int first = paragraphIndex(e.start);
            String old = e.end > e.start ? getTextRange(e.start, e.end - e.start) : "";
            int removed = count(old, '\n'), added = count(e.text, '\n');
            Para keep = paras.get(Math.min(first, paras.size() - 1));
            for (int i = 0; i < removed && first + 1 < paras.size(); i++) paras.remove(first + 1);
            for (int i = 0; i < added; i++) paras.add(first + 1, new Para(keep.style, keep.hung));
        });
        addExtendedModifyListener(e -> {
            if (!internal && e.length > 0) {
                StyleRange r = rangeFor(e.start, e.length, typing);
                setStyleRange(r);
            }
            edited = true;
            if (!internal) {
                syncParagraphs();
                convertWikiLink();
                host.changed(this);
            }
        });
        addCaretListener(e -> {
            if (!internal && getSelectionCount() == 0) typing = styleForTyping(e.caretOffset);
            host.selectionChanged(this);
        });
        addListener(SWT.Selection, e -> host.selectionChanged(this));
        addListener(SWT.FocusIn, e -> host.focused(this));
        addVerifyKeyListener(e -> {
            if (host.keyFirst(this, e.keyCode, e.stateMask, e.character)) {
                e.doit = false;
                return;
            }
            boolean handled = switch (e.keyCode) {
                case SWT.CR, SWT.KEYPAD_CR -> (e.stateMask & SWT.SHIFT) != 0 ? insertTyped(String.valueOf('\r')) : handleNewline();
                case SWT.TAB -> (e.stateMask & SWT.CTRL) != 0 ? insertTyped("\t") : handleTab((e.stateMask & SWT.SHIFT) != 0);
                case SWT.BS -> e.stateMask == 0 && handleBackspace();
                case SWT.ESC -> {
                    host.escape(this);
                    yield true;
                }
                case SWT.ARROW_UP, SWT.ARROW_DOWN -> {
                    if ((e.stateMask & (SWT.ALT | SWT.SHIFT)) == (SWT.ALT | SWT.SHIFT)) yield moveParagraphs(e.keyCode == SWT.ARROW_UP);
                    if (e.stateMask != 0) yield false;
                    yield caretOnEdgeLine(e.keyCode == SWT.ARROW_UP) && host.arrowOut(this, e.keyCode == SWT.ARROW_UP);
                }
                default -> false;
            };
            if (handled) e.doit = false;
        });
        addListener(SWT.MouseDown, e -> {
            if (e.button == 1 && (e.stateMask & SWT.MODIFIER_MASK) == 0) toggleCheckbox(e.x, e.y);
        });
        addListener(SWT.MouseUp, e -> {
            if (e.button != 1 || (e.stateMask & SWT.ALT) != 0 || getSelectionCount() > 0) return;
            String link = linkAt(e.x, e.y);
            if (link != null) host.openLink(link);
        });
        addListener(SWT.MouseMove, e -> setCursor(linkAt(e.x, e.y) != null ? getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null));
        addPaintObjectListener(e -> {
            if (!(e.style.data instanceof CharStyle cs) || cs.attachment() == null) return;
            Image img = picture(cs.attachment());
            int w = e.style.metrics.width, h = e.style.metrics.ascent;
            int y = e.y + e.ascent - h;
            if (img != null) {
                var b = img.getBounds();
                e.gc.drawImage(img, 0, 0, b.width, b.height, e.x, y, w, h);
            } else {
                e.gc.setForeground(Gfx.color(Gfx.GRAY_TEXT));
                e.gc.drawRectangle(e.x, y, w - 1, h - 1);
                e.gc.drawText(cs.attachment().fileName(), e.x + 4, y + 4, true);
            }
        });
        addListener(SWT.Dispose, e -> pictures.values().forEach(Image::dispose));
    }

    @Override
    protected void checkSubclass() {
        // a subclass of the custom widget StyledText
    }

    public void setHost(Host h) { host = h; }
    public double zoom() { return zoom; }
    public CharStyle typing() { return typing; }
    public void setTyping(CharStyle s) { typing = s; }

    /** The style typed into the editor while it's empty (a table cell's font and colour). */
    public void setBase(CharStyle s) {
        base = s;
        if (getCharCount() == 0) typing = s;
    }

    int px(double v) { return (int) Math.round(v * zoom); }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private int paragraphIndex(int offset) {
        String t = getText();
        int n = 0;
        for (int i = 0; i < Math.min(offset, t.length()); i++) if (t.charAt(i) == '\n') n++;
        return n;
    }

    // MARK: Content

    public void setContent(RText rt) {
        internal = true;
        try {
            setText(rt.text.replace(RText.LINE_BREAK, '\r'));
            List<StyleRange> ranges = new ArrayList<>();
            for (RText.Span s : rt.spans) if (s.end() > s.start()) ranges.add(rangeFor(s.start(), s.end() - s.start(), s.style()));
            setStyleRanges(ranges.toArray(new StyleRange[0]));
            paras.clear();
            for (ParaStyle p : rt.paras) paras.add(new Para(p, false));
            while (paras.size() < RText.paragraphCount(rt.text)) paras.add(new Para(ParaStyle.DEFAULT, false));
            typing = rt.spans.isEmpty() ? base : rt.spans.get(rt.spans.size() - 1).style().forTyping();
            if (typing.link() != null) typing = typing.withLink(null);
        } finally {
            internal = false;
        }
        edited = false;
        syncParagraphs();
    }

    public RText getContent() {
        String text = getText();
        List<RText.Span> spans = new ArrayList<>();
        int at = 0;
        for (StyleRange r : getStyleRanges(true)) {
            if (r.start > at) spans.add(new RText.Span(at, r.start, base));
            CharStyle cs = r.data instanceof CharStyle c ? c : base;
            spans.add(new RText.Span(r.start, r.start + r.length, cs));
            at = r.start + r.length;
        }
        if (at < text.length()) spans.add(new RText.Span(at, text.length(), base));
        List<ParaStyle> ps = new ArrayList<>();
        for (Para p : paras) ps.add(p.style);
        return new RText(text.replace('\r', RText.LINE_BREAK), merge(spans), ps);
    }

    private static List<RText.Span> merge(List<RText.Span> spans) {
        List<RText.Span> out = new ArrayList<>();
        for (RText.Span s : spans) {
            RText.Span last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.end() == s.start() && last.style().equals(s.style()) && s.style().attachment() == null) {
                out.set(out.size() - 1, new RText.Span(last.start(), s.end(), s.style()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** A snapshot for Undo: the content and the selection. */
    public record Snapshot(RText content, int start, int end) {}

    public Snapshot snapshot() {
        Point sel = getSelection();
        return new Snapshot(getContent(), sel.x, sel.y);
    }

    public void restore(Snapshot s) {
        setContent(s.content);
        edited = true;
        int len = getCharCount();
        setSelection(Math.min(s.start, len), Math.min(s.end, len));
        host.changed(this);
    }

    /** True when nothing but spaces and empty lines is in it. */
    public boolean isBlank() {
        String t = getText();
        return t.replace('￼', 'x').isBlank();
    }

    // MARK: Styles

    StyleRange rangeFor(int start, int length, CharStyle cs) {
        StyleRange r = new StyleRange();
        r.start = start;
        r.length = length;
        r.data = cs;
        r.font = Gfx.font(cs, zoom);
        RGB fg = cs.link() != null ? Gfx.LINK : cs.color() == null ? autoColor : cs.color();
        r.foreground = Gfx.color(fg);
        r.background = Gfx.color(cs.background());
        r.underline = cs.underline() || cs.link() != null;
        r.strikeout = cs.strike();
        if (cs.script() > 0) r.rise = (int) Math.round(cs.size() * 0.33 * zoom);
        else if (cs.script() < 0) r.rise = -(int) Math.round(cs.size() * 0.2 * zoom);
        if (cs.attachment() != null) {
            double[] size = attachmentSize(cs.attachment());
            r.metrics = new GlyphMetrics(Math.max(1, px(size[1])), 0, Math.max(1, px(size[0])));
        }
        return r;
    }

    private double[] attachmentSize(CharStyle.Attachment a) {
        if (a.width() > 0 && a.height() > 0) return new double[] { a.width(), a.height() };
        Image img = picture(a);
        if (img == null) return new double[] { 120, 32 };
        var b = img.getBounds();
        double w = b.width, h = b.height;
        if (w > 480) {
            h = h * 480 / w;
            w = 480;
        }
        return new double[] { w, h };
    }

    private Image picture(CharStyle.Attachment a) {
        if (a.data() == null) return null;
        if (pictures.containsKey(a)) return pictures.get(a);
        Image img = null;
        try {
            img = new Image(getDisplay(), new ImageData(new ByteArrayInputStream(a.data())));
        } catch (RuntimeException e) {
            img = null;
        }
        pictures.put(a, img);
        return img;
    }

    public CharStyle styleAt(int offset) {
        if (offset < 0 || offset >= getCharCount()) return base;
        StyleRange r = getStyleRangeAtOffset(offset);
        return r != null && r.data instanceof CharStyle cs ? cs : base;
    }

    /** What's typed at a caret: the formatting of the character before it (at a paragraph's start: after it). */
    private CharStyle styleForTyping(int caret) {
        if (getCharCount() == 0) return base;
        String t = getText();
        CharStyle s;
        if (caret == 0 || t.charAt(caret - 1) == '\n') {
            s = caret < t.length() && t.charAt(caret) != '\n' ? styleAt(caret) : styleAt(caret - 1);
        } else {
            s = styleAt(caret - 1);
        }
        s = s.forTyping();
        if (s.link() != null && (caret >= t.length() || !s.link().equals(styleAt(caret).link()))) s = s.withLink(null);
        return s;
    }

    /** Changes the formatting of the selection, or of what's typed next when nothing is selected. */
    public void changeChars(String action, UnaryOperator<CharStyle> f) {
        Point sel = getSelection();
        if (sel.x == sel.y) {
            typing = f.apply(typing);
            host.selectionChanged(this);
            return;
        }
        host.willChange(this, action, false);
        internal = true;
        try {
            List<StyleRange> out = new ArrayList<>();
            int at = sel.x;
            for (StyleRange r : getStyleRanges(sel.x, sel.y - sel.x, true)) {
                int a = Math.max(r.start, sel.x), b = Math.min(r.start + r.length, sel.y);
                if (a > at) out.add(rangeFor(at, a - at, f.apply(base)));
                CharStyle cs = r.data instanceof CharStyle c ? c : base;
                if (b > a) out.add(rangeFor(a, b - a, f.apply(cs)));
                at = Math.max(at, b);
            }
            if (at < sel.y) out.add(rangeFor(at, sel.y - at, f.apply(base)));
            replaceStyleRanges(sel.x, sel.y - sel.x, out.toArray(new StyleRange[0]));
        } finally {
            internal = false;
        }
        edited = true;
        syncParagraphs();
        host.changed(this);
        host.selectionChanged(this);
    }

    /** Formatting of the selection (or of the typing) for the ribbon: one style per run with text in it. */
    public List<CharStyle> selectionStyles() {
        Point sel = getSelection();
        List<CharStyle> out = new ArrayList<>();
        if (sel.x == sel.y) {
            out.add(typing);
            return out;
        }
        String t = getText();
        int at = sel.x;
        for (StyleRange r : getStyleRanges(sel.x, sel.y - sel.x, true)) {
            int a = Math.max(r.start, sel.x), b = Math.min(r.start + r.length, sel.y);
            if (a > at && !t.substring(at, a).isBlank()) out.add(base);
            if (b > a && (!t.substring(a, b).isBlank() || out.isEmpty())) out.add(r.data instanceof CharStyle c ? c : base);
            at = Math.max(at, b);
        }
        if (out.isEmpty()) out.add(styleAt(sel.x));
        return out;
    }

    // MARK: Paragraphs

    /** [start, end) of each paragraph the selection touches (end excludes the "\n"). */
    public List<int[]> selectedParagraphs() {
        Point sel = getSelection();
        String t = getText();
        List<int[]> out = new ArrayList<>();
        int start = sel.x == 0 ? 0 : t.lastIndexOf('\n', sel.x - 1) + 1;
        while (true) {
            int end = t.indexOf('\n', start);
            if (end < 0) end = t.length();
            out.add(new int[] { start, end });
            // Go on while the selection holds a character of the next paragraph.
            if (end >= t.length() || sel.y <= end + 1) break;
            start = end + 1;
        }
        return out;
    }

    public int[] paragraphAt(int offset) {
        String t = getText();
        int start = offset == 0 ? 0 : t.lastIndexOf('\n', offset - 1) + 1;
        int end = t.indexOf('\n', offset);
        return new int[] { start, end < 0 ? t.length() : end };
    }

    public ParaStyle paragraphStyle(int index) {
        return paras.get(Math.max(0, Math.min(index, paras.size() - 1))).style;
    }

    public void changeParagraphs(String action, UnaryOperator<ParaStyle> f) {
        host.willChange(this, action, false);
        for (int[] p : selectedParagraphs()) {
            int i = paragraphIndex(p[0]);
            if (i < paras.size()) paras.get(i).style = f.apply(paras.get(i).style);
        }
        edited = true;
        syncParagraphs();
        host.changed(this);
    }

    /** Applies the paragraphs' alignment and indents to the widget's lines, and the hanging indent of list items. */
    public void syncParagraphs() {
        if (syncing || isDisposed()) return;
        syncing = true;
        try {
            String t = getText();
            int lines = getLineCount();
            int para = 0;
            for (int line = 0; line < lines; line++) {
                int off = getOffsetAtLine(line);
                boolean first = line == 0 || t.charAt(off - 1) == '\n';
                if (line > 0 && t.charAt(off - 1) == '\n') para++;
                Para p = paras.get(Math.min(para, paras.size() - 1));
                if (first) {
                    int end = t.indexOf('\n', off);
                    String text = t.substring(off, end < 0 ? t.length() : end);
                    Info info = Paragraphs.parse(text);
                    if ((info.list != 0 || info.tag != null) && info.contentStart() > 0 && info.contentStart() <= text.length()) {
                        setLineIndent(line, 1, px(p.style.firstIndent()));
                        int x0 = getLocationAtOffset(off).x, x1 = getLocationAtOffset(off + info.contentStart()).x;
                        double head = p.style.firstIndent() + Math.max(0, x1 - x0) / zoom;
                        if (Math.abs(head - p.style.headIndent()) > 0.5 || !p.hung) {
                            p.style = p.style.withIndents(p.style.firstIndent(), Math.round(head * 2) / 2.0);
                            p.hung = true;
                        }
                    } else if (p.hung) {
                        p.style = p.style.withIndents(p.style.firstIndent(), p.style.firstIndent());
                        p.hung = false;
                    }
                }
                int align = switch (p.style.align()) {
                    case ParaStyle.CENTER -> SWT.CENTER;
                    case ParaStyle.RIGHT -> SWT.RIGHT;
                    default -> SWT.LEFT;
                };
                if (getLineAlignment(line) != align) setLineAlignment(line, 1, align);
                boolean justify = p.style.align() == ParaStyle.JUSTIFIED;
                if (getLineJustify(line) != justify) setLineJustify(line, 1, justify);
                int indent = px(first ? p.style.firstIndent() : p.style.headIndent());
                if (getLineIndent(line) != indent) setLineIndent(line, 1, indent);
                int wrap = px(p.style.headIndent());
                if (getLineWrapIndent(line) != wrap) setLineWrapIndent(line, 1, wrap);
                int vertical = first && line > 0 ? px(p.style.spaceBefore()) : 0;
                if (getLineVerticalIndent(line) != vertical) setLineVerticalIndent(line, vertical);
            }
        } finally {
            syncing = false;
        }
    }

    // MARK: Editing

    /** Inserts text at the selection in the typing style (Enter, Shift+Enter, Ctrl+Tab). */
    boolean insertTyped(String s) {
        Point sel = getSelection();
        host.willChange(this, "Typing", true);
        CharStyle style = typing.withLink(null);
        replace(sel.x, sel.y - sel.x, s, style);
        setCaretOffset(sel.x + s.length());
        typing = style;
        showSelection();
        return true;
    }

    /** Replaces a range with text in a style, as one change. */
    public void replace(int start, int length, String text, CharStyle style) {
        internal = true;
        try {
            replaceTextRange(start, length, text);
            if (!text.isEmpty()) setStyleRange(rangeFor(start, text.length(), style));
        } finally {
            internal = false;
        }
        edited = true;
        syncParagraphs();
        host.changed(this);
    }

    /** The style for marker text inserted at a place: the text's there, without link or attachment. */
    private CharStyle markerStyle(int at) {
        String t = getText();
        CharStyle s = at < t.length() && t.charAt(at) != '\n' ? styleAt(at) : typing;
        return s.forTyping().withLink(null);
    }

    private record Edit(int start, int length, String text) {}

    /** Several replacements as one undoable change, keeping the caret where it was in the text. */
    private void replaceAll(List<Edit> edits, String action) {
        if (edits.isEmpty()) return;
        host.willChange(this, action, false);
        Point sel = getSelection();
        int shift = 0;
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(a.start, b.start));
        for (Edit e : sorted) if (e.start <= sel.x) shift += e.text.length() - e.length;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Edit e = sorted.get(i);
            CharStyle s = markerStyle(e.start + e.length < getCharCount() ? e.start + e.length : e.start);
            internal = true;
            try {
                replaceTextRange(e.start, e.length, e.text);
                if (!e.text.isEmpty()) setStyleRange(rangeFor(e.start, e.text.length(), s));
            } finally {
                internal = false;
            }
        }
        edited = true;
        int len = getCharCount();
        int caret = Math.max(0, Math.min(len, sel.x + shift));
        setSelection(caret, Math.min(len, caret + (sel.y - sel.x)));
        syncParagraphs();
        host.changed(this);
    }

    private String paragraphText(int[] p) { return getText().substring(p[0], p[1]); }

    public void toggleBullets() {
        List<int[]> ps = selectedParagraphs();
        boolean all = ps.stream().allMatch(p -> Paragraphs.parse(paragraphText(p)).isBullet());
        List<Edit> edits = new ArrayList<>();
        for (int[] p : ps) {
            Info info = Paragraphs.parse(paragraphText(p));
            edits.add(new Edit(p[0] + info.indent, info.listLength, all ? "" : Paragraphs.BULLET));
        }
        replaceAll(edits, "Bullets");
    }

    public void toggleNumbering() {
        List<int[]> ps = selectedParagraphs();
        boolean all = ps.stream().allMatch(p -> Paragraphs.parse(paragraphText(p)).isNumber());
        List<Edit> edits = new ArrayList<>();
        int n = 1;
        for (int[] p : ps) {
            Info info = Paragraphs.parse(paragraphText(p));
            edits.add(new Edit(p[0] + info.indent, info.listLength, all ? "" : n++ + ". "));
        }
        replaceAll(edits, "Numbering");
    }

    /** Adds the tag to the selected paragraphs, or removes it if they all have it. */
    public void toggleTag(TagKind kind) {
        List<int[]> ps = selectedParagraphs();
        boolean all = ps.stream().allMatch(p -> Paragraphs.parse(paragraphText(p)).tag == kind);
        List<Edit> edits = new ArrayList<>();
        for (int[] p : ps) {
            Info info = Paragraphs.parse(paragraphText(p));
            edits.add(new Edit(p[0] + info.indent + info.listLength, info.tagLength, all ? "" : kind.symbol + " "));
        }
        replaceAll(edits, kind.title);
    }

    public void removeTags() {
        List<Edit> edits = new ArrayList<>();
        for (int[] p : selectedParagraphs()) {
            Info info = Paragraphs.parse(paragraphText(p));
            if (info.tagLength > 0) edits.add(new Edit(p[0] + info.indent + info.listLength, info.tagLength, ""));
        }
        replaceAll(edits, "Remove Tag");
    }

    /** Indent (+1) or outdent (-1) whole paragraphs by 28 points. */
    public void indent(int direction) {
        changeParagraphs(direction > 0 ? "Indent" : "Outdent", p -> p.withIndents(Math.max(0, p.firstIndent() + 28 * direction),
                Math.max(0, p.headIndent() + 28 * direction)));
    }

    /** Home ▸ Styles: the paragraphs take the style's formatting. */
    public void applyStyle(Paragraphs.Style style) {
        List<int[]> ps = selectedParagraphs();
        int a = ps.get(0)[0], b = ps.get(ps.size() - 1)[1];
        Point sel = getSelection();
        setSelection(a, b);
        if (b > a) {
            changeChars(style.title, s -> style.apply(s).withLink(s.link()).withTip(s.tip()));
        }
        setSelection(sel.x, sel.y);
        typing = style.apply(typing);
    }

    public void clearFormatting() {
        changeChars("Clear Formatting", s -> CharStyle.DEFAULT.withLink(s.link()).withTip(s.tip()).withAttachment(s.attachment()));
        changeParagraphs("Clear Formatting", p -> ParaStyle.DEFAULT.withIndents(p.firstIndent(), p.headIndent()).withExtras(p.extras()));
    }

    /** Return: lists and To Dos continue (an empty item steps out, then ends the list); after a heading, Normal. */
    private boolean handleNewline() {
        Point sel = getSelection();
        if (sel.x != sel.y) {
            return insertTyped("\n");
        }
        int caret = sel.x;
        int[] p = paragraphAt(caret);
        String text = paragraphText(p);
        Info info = Paragraphs.parse(text);
        boolean todo = info.tag == TagKind.TODO;
        if (info.list != 0 || todo) {
            int prefixEnd = info.indent + info.listLength + (todo ? info.tagLength : 0);
            if (caret < p[0] + prefixEnd) return insertTyped("\n");
            if (text.length() <= prefixEnd) {
                if (info.indent > 0) replaceAll(List.of(new Edit(p[0], 1, "")), "Outdent");
                else replaceAll(List.of(new Edit(p[0] + info.indent, prefixEnd - info.indent, "")), "End List");
                return true;
            }
            String next = text.substring(0, info.indent);
            if (info.isBullet()) next += Paragraphs.BULLET;
            else if (info.isNumber()) next += (info.list + 1) + ". ";
            if (todo) next += TagKind.TODO.symbol + " ";
            return insertTyped("\n" + next);
        }
        String indent = text.substring(0, info.indent);
        boolean atEnd = caret >= p[1];
        Paragraphs.Style heading = text.length() > 0 && atEnd ? Paragraphs.Style.headingOf(styleAt(p[0])) : null;
        if (!indent.isEmpty() && text.length() == info.indent && caret >= p[0] + info.indent) {
            replaceAll(List.of(new Edit(p[0], 1, "")), "Outdent");
            return true;
        }
        if (indent.isEmpty() && heading == null) return insertTyped("\n");
        host.willChange(this, "Typing", true);
        CharStyle now = typing.withLink(null);
        CharStyle next = heading != null ? Paragraphs.Style.NORMAL.apply(now) : now;
        internal = true;
        try {
            replaceTextRange(caret, 0, "\n" + indent);
            setStyleRange(rangeFor(caret, 1, now));
            if (!indent.isEmpty()) setStyleRange(rangeFor(caret + 1, indent.length(), next));
        } finally {
            internal = false;
        }
        edited = true;
        setCaretOffset(caret + 1 + indent.length());
        typing = next;
        syncParagraphs();
        host.changed(this);
        showSelection();
        return true;
    }

    /** Backspace at the start of a paragraph's text removes its tag or marker, or steps out one level. */
    private boolean handleBackspace() {
        Point sel = getSelection();
        if (sel.x != sel.y || sel.x == 0) return false;
        int[] p = paragraphAt(sel.x);
        Info info = Paragraphs.parse(paragraphText(p));
        int offset = sel.x - p[0];
        if (offset <= 0) return false;
        if (info.tagLength > 0 && offset == info.contentStart()) {
            replaceAll(List.of(new Edit(p[0] + info.indent + info.listLength, info.tagLength, "")), "Remove Tag");
            return true;
        }
        if (info.listLength > 0 && offset == info.indent + info.listLength) {
            replaceAll(List.of(new Edit(p[0] + info.indent, info.listLength, "")), "Remove Bullet");
            return true;
        }
        if (info.indent > 0 && offset == info.indent) {
            replaceAll(List.of(new Edit(p[0], 1, "")), "Outdent");
            return true;
        }
        return false;
    }

    /** Tab: after text in a plain paragraph a table starts; in list items it indents; Shift+Tab outdents. */
    private boolean handleTab(boolean outdent) {
        List<int[]> ps = selectedParagraphs();
        Point sel = getSelection();
        if (!outdent && ps.size() == 1 && sel.x == sel.y) {
            int[] p = ps.get(0);
            Info info = Paragraphs.parse(paragraphText(p));
            if (info.list == 0 && info.tag == null && sel.x > p[0] && !paragraphText(p).isBlank()
                    && host.tabToTable(this, p[0], p[1])) return true;
        }
        boolean anyMarked = ps.stream().anyMatch(p -> {
            Info i = Paragraphs.parse(paragraphText(p));
            return i.list != 0 || i.tag != null;
        });
        if (!anyMarked && !outdent) return insertTyped("\t");
        List<Edit> edits = new ArrayList<>();
        String t = getText();
        for (int[] p : ps) {
            if (outdent) {
                if (p[0] < t.length() && (t.charAt(p[0]) == '\t' || t.charAt(p[0]) == ' ')) edits.add(new Edit(p[0], 1, ""));
            } else {
                edits.add(new Edit(p[0], 0, "\t"));
            }
        }
        if (edits.isEmpty()) return outdent;
        replaceAll(edits, outdent ? "Outdent" : "Indent");
        return true;
    }

    /** Alt+Shift+Up / Down: the selected paragraphs move past the neighbouring one. */
    private boolean moveParagraphs(boolean up) {
        List<int[]> ps = selectedParagraphs();
        String t = getText();
        int blockStart = ps.get(0)[0], blockEnd = ps.get(ps.size() - 1)[1];
        int[] neighbor;
        if (up) {
            if (blockStart == 0) return true;
            neighbor = paragraphAt(blockStart - 1);
        } else {
            if (blockEnd >= t.length()) return true;
            neighbor = paragraphAt(blockEnd + 1);
        }
        RText all = getContent();
        RText block = all.slice(blockStart, blockEnd), other = all.slice(neighbor[0], neighbor[1]);
        RText nl = all.slice(up ? neighbor[1] : blockEnd, (up ? neighbor[1] : blockEnd) + 1);
        int start = up ? neighbor[0] : blockStart, end = up ? blockEnd : neighbor[1];
        RText first = up ? block : other, second = up ? other : block;
        RText joined = first.concat(nl).concat(second);
        host.willChange(this, up ? "Move Paragraph Up" : "Move Paragraph Down", false);
        RText before = all.slice(0, start), after = all.slice(end, all.length());
        setContent(before.concat(joined).concat(after));
        edited = true;
        int movedStart = up ? start : start + other.length() + 1;
        setSelection(movedStart, movedStart + block.length());
        host.changed(this);
        return true;
    }

    boolean caretOnEdgeLine(boolean top) {
        int line = getLineAtOffset(getCaretOffset());
        if (top) {
            if (line > 0) return false;
            Point loc = getLocationAtOffset(getCaretOffset());
            return loc.y <= getLinePixel(0) + 2;
        }
        if (line < getLineCount() - 1) return false;
        Point loc = getLocationAtOffset(getCaretOffset());
        Point end = getLocationAtOffset(getCharCount());
        return loc.y >= end.y - 2;
    }

    /** A click on ☐ or ☑ at a To Do's start checks or unchecks it. */
    private void toggleCheckbox(int x, int y) {
        int offset;
        try {
            offset = getOffsetAtPoint(new Point(x, y));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (offset < 0 || offset >= getCharCount()) return;
        String ch = getTextRange(offset, 1);
        if (!ch.equals(TagKind.TODO.symbol) && !ch.equals(TagKind.TODO_DONE)) return;
        int[] p = paragraphAt(offset);
        Info info = Paragraphs.parse(paragraphText(p));
        if (info.tag != TagKind.TODO || offset != p[0] + info.indent + info.listLength) return;
        boolean check = ch.equals(TagKind.TODO.symbol);
        host.willChange(this, check ? "Check" : "Uncheck", false);
        CharStyle s = styleAt(offset);
        Point sel = getSelection();
        replace(offset, 1, check ? TagKind.TODO_DONE : TagKind.TODO.symbol, s);
        getDisplay().asyncExec(() -> {
            if (!isDisposed()) setSelection(sel.x, sel.y);
        });
    }

    public String linkAt(int x, int y) {
        int offset;
        try {
            offset = getOffsetAtPoint(new Point(x, y));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (offset < 0 || offset >= getCharCount()) return null;
        return styleAt(offset).link();
    }

    /** The link at the caret or the selection's start, with its range; null when there is none. */
    public int[] linkRange(int offset) {
        String link = styleAt(offset).link();
        if (link == null && offset > 0) {
            offset--;
            link = styleAt(offset).link();
        }
        if (link == null) return null;
        int a = offset, b = offset;
        while (a > 0 && link.equals(styleAt(a - 1).link())) a--;
        while (b < getCharCount() && link.equals(styleAt(b).link())) b++;
        return new int[] { a, b };
    }

    /** Makes the range a link; with an empty range, inserts the title as a link. */
    public void addLink(String url, String title, int start, int end) {
        if (end > start) {
            setSelection(start, end);
            changeChars("Link", s -> s.withLink(url));
        } else {
            host.willChange(this, "Link", false);
            String text = title == null || title.isEmpty() ? url : title;
            replace(start, 0, text, typing.withLink(url));
            setCaretOffset(start + text.length());
        }
        typing = typing.withLink(null);
    }

    public void removeLink() {
        int[] r = linkRange(getSelection().x);
        if (r == null) return;
        Point sel = getSelection();
        setSelection(r[0], r[1]);
        changeChars("Remove Link", s -> s.withLink(null));
        setSelection(sel.x, sel.y);
    }

    /** [[Page Name]] just typed: the words become a link to the page. */
    private void convertWikiLink() {
        int caret = getCaretOffset();
        String t = getText();
        if (caret < 4 || !t.startsWith("]]", caret - 2)) return;
        int open = t.lastIndexOf("[[", caret - 2);
        if (open < 0 || t.substring(open, caret).contains("\n")) return;
        String title = t.substring(open + 2, caret - 2).trim();
        if (title.isEmpty()) return;
        String url = host.pageLink(title);
        if (url == null) return;
        CharStyle s = styleAt(open).forTyping();
        getDisplay().asyncExec(() -> {
            if (isDisposed() || !getText().startsWith("[[" + t.substring(open + 2, caret - 2) + "]]", open)) return;
            replace(open, caret - open, title, s.withLink(url));
            setCaretOffset(open + title.length());
            typing = s.withLink(null);
        });
    }

    // MARK: Clipboard

    /** Pastes plain text in the style where it goes (line ends become paragraphs). */
    @Override
    public void paste() {
        Clipboard cb = new Clipboard(getDisplay());
        try {
            Object o = cb.getContents(TextTransfer.getInstance());
            if (o instanceof String s && !s.isEmpty()) insertTyped(s.replace("\r\n", "\n").replace('\r', '\n'));
        } finally {
            cb.dispose();
        }
    }

    @Override
    public void invokeAction(int action) {
        if (action == ST.PASTE) {
            paste();
            return;
        }
        if (action == ST.NEW_LINE) {
            handleNewline();
            return;
        }
        super.invokeAction(action);
    }

    /** Re-applies formatting after a zoom change. */
    public void setZoom(double z) {
        if (z == zoom) return;
        RText content = getContent();
        Point sel = getSelection();
        boolean wasEdited = edited;
        zoom = z;
        setTabStops(new int[] { px(28) });
        setMargins(px(4), px(4), px(4), px(4));
        setContent(content);
        edited = wasEdited;
        setSelection(sel.x, sel.y);
    }

    public static boolean isDisplayThread() { return Display.getCurrent() != null; }
}
