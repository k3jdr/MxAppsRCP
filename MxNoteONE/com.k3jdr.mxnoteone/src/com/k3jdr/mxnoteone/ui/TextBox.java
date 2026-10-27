package com.k3jdr.mxnoteone.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.text.RText;
import com.k3jdr.mxnoteone.text.RichCodec;
import com.k3jdr.mxnoteone.text.RtfWriter;

/** A text container: rich text that grows with what's typed (TextElementView). */
final class TextBox extends ElementView implements NoteText.Host {
    final NoteText text;
    private double textHeight = 24;
    private boolean fitting;

    TextBox(PageView page, PageElement element) {
        super(page, element);
        text = new NoteText(this, SWT.NONE, page.zoom);
        text.setBackground(page.paperColor());
        text.setHost(this);
        if (element.rtfd != null) text.setContent(RichCodec.decode(element.rtfd));
        Menu menu = new Menu(text);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            page.fillTextMenu(menu, this);
        });
        text.setMenu(menu);
        text.addListener(SWT.FocusOut, e -> page.blankCheck(this));
        QuickHelp.provider(text, (c, p) -> {
            String link = text.linkAt(p.x, p.y);
            if (link != null) {
                String tip = text.styleAt(safeOffset(p.x, p.y)).tip();
                return new QuickHelpItem("Link: " + page.linkTitle(link),
                        (tip != null ? RtfWriter.tipText(tip).strip() + "\n" : "") + page.linkDetail(link)
                                + "\nClick to open it (Alt+click to type in it).", "Ctrl+K to edit");
            }
            return new QuickHelpItem("Text Box", "Type here. Select text to format it from the Home tab; drag the bar above the box to move it, its right edge to resize. Tab after a word starts a table.");
        });
        place();
    }

    private int safeOffset(int x, int y) {
        try {
            return text.getOffsetAtPoint(new org.eclipse.swt.graphics.Point(x, y));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    double minWidth() { return 80; }

    @Override
    Control content() { return text; }

    @Override
    double contentHeight() { return textHeight; }

    @Override
    boolean isEditing() { return text.isFocusControl(); }

    @Override
    void place() {
        measure();
        super.place();
        text.syncParagraphs();
    }

    private void measure() {
        int w = Math.max(1, px(element.width) - 1 - HANDLE);
        int h = text.computeSize(w, SWT.DEFAULT).y;
        textHeight = Math.max(24, h / page.zoom);
    }

    /** The text changed: the container grows or shrinks with it, pushing the ones under it. */
    void fit() {
        if (fitting) return;
        fitting = true;
        try {
            double oldBottom = element.y + element.height;
            double old = textHeight;
            measure();
            if (Math.abs(old - textHeight) > 0.5) {
                super.place();
                page.grew(this, oldBottom);
            }
        } finally {
            fitting = false;
        }
    }

    boolean isBlank() { return text.isBlank(); }

    @Override
    PageElement snapshot() {
        if (text.edited) {
            RText content = text.getContent();
            element.rtfd = RichCodec.encode(content);
        }
        return element;
    }

    @Override
    String indexText() {
        RText content = text.getContent();
        StringBuilder sb = new StringBuilder(content.text);
        String last = null;
        for (RText.Span s : content.spans) {
            if (s.style().tip() != null && !s.style().tip().equals(last)) sb.append('\n').append(RtfWriter.tipText(s.style().tip()));
            last = s.style().tip();
        }
        return sb.toString();
    }

    void focus(boolean atEnd) {
        text.setFocus();
        text.setSelection(atEnd ? text.getCharCount() : 0);
    }

    /** Puts the caret on the first or last line at a page x position (arrow keys from another container). */
    void focus(boolean lastLine, double pageX) {
        text.setFocus();
        int x = px(pageX - element.x);
        int y = lastLine ? text.getClientArea().height - px(6) : px(6);
        int offset;
        try {
            offset = text.getOffsetAtPoint(new org.eclipse.swt.graphics.Point(Math.max(0, x), Math.max(0, y)));
        } catch (RuntimeException e) {
            offset = -1;
        }
        if (offset < 0) offset = lastLine ? text.getCharCount() : 0;
        text.setSelection(offset);
    }

    @Override
    void fillMenu(Menu menu) {
        item(menu, "Select All Text", () -> {
            text.setFocus();
            text.selectAll();
        });
        new MenuItem(menu, SWT.SEPARATOR);
        super.fillMenu(menu);
    }

    // MARK: NoteText.Host

    @Override
    public void willChange(NoteText t, String action, boolean typing) {
        page.textWillChange(this, action, typing);
    }

    @Override
    public void changed(NoteText t) {
        fit();
        page.contentChanged();
    }

    @Override
    public void escape(NoteText t) {
        page.select(this);
    }

    @Override
    public boolean arrowOut(NoteText t, boolean up) {
        int caret = t.getCaretOffset();
        double x = element.x + t.getLocationAtOffset(caret).x / page.zoom;
        return page.moveFocus(this, up, x);
    }

    @Override
    public boolean tabToTable(NoteText t, int paraStart, int paraEnd) {
        return page.convertParagraphToTable(this, paraStart, paraEnd);
    }

    @Override
    public void openLink(String url) {
        page.openLink(url);
    }

    @Override
    public void focused(NoteText t) {
        page.textFocused(this);
    }

    @Override
    public void selectionChanged(NoteText t) {
        page.formattingChanged();
    }

    @Override
    public String pageLink(String title) {
        return page.store.linkForPage(title);
    }
}
