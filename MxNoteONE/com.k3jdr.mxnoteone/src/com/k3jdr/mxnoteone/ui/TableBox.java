package com.k3jdr.mxnoteone.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextLayout;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.model.TableData;
import com.k3jdr.mxnoteone.table.Formula;
import com.k3jdr.mxnoteone.table.TableValues;
import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.Fonts;
import com.k3jdr.mxnoteone.text.ParaStyle;
import com.k3jdr.mxnoteone.text.RText;
import com.k3jdr.mxnoteone.text.RichCodec;

/**
 * A OneNote-style table (TableViews.swift): the table draws its cells; one rich-text editor moves to the cell being
 * typed in, so the Home ribbon formats cell text, and with cells selected it formats all of them at once. Tab and
 * Return grow the table, Backspace in an empty new column or row takes it away again; drag across cells,
 * Shift-click or Shift-arrows to select a block, Ctrl-click for single cells; drag a column border to resize it,
 * double-click it to fit. Right-click for rows, columns, shading, sorting, header row, borders and the clipboard.
 */
public final class TableBox extends ElementView implements NoteText.Host {
    static final double PAD_X = 6, PAD_Y = 4, MIN_ROW = 24;

    private TableData data;
    private TableValues values;
    final Canvas grid;
    private NoteText editor;
    private int er = -1, ec = -1;
    private TableData editBefore;
    private double editWidthBefore;
    private int ar = -1, ac = -1, fr = -1, fc = -1;
    private final Set<Long> extra = new HashSet<>();
    private double[] colW = new double[0], rowH = new double[0];
    private final Map<Long, TextLayout> layouts = new HashMap<>();
    private final Map<Long, RText> richCache = new HashMap<>();
    private int resizeColumn = -1;
    private double resizeStartX;
    private double[] resizeWidths;
    private TableData resizeBefore;
    private int[] dragStart;
    private int addedRow = -1, addedColumn = -1;

    TableBox(PageView page, PageElement element) {
        super(page, element);
        data = element.table;
        data.normalize();
        values = new TableValues(data);
        grid = new Canvas(this, SWT.DOUBLE_BUFFERED);

        grid.addPaintListener(e -> paint(e.gc));
        grid.addListener(SWT.MouseDown, this::mouseDown);
        grid.addListener(SWT.MouseMove, this::mouseMove);
        grid.addListener(SWT.MouseUp, e -> mouseUp());
        grid.addListener(SWT.MouseDoubleClick, this::doubleClick);
        grid.addListener(SWT.KeyDown, this::gridKey);
        grid.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS || e.detail == SWT.TRAVERSE_RETURN
                    || e.detail == SWT.TRAVERSE_ESCAPE || e.detail == SWT.TRAVERSE_ARROW_NEXT || e.detail == SWT.TRAVERSE_ARROW_PREVIOUS) {
                e.doit = false;
            }
        });
        grid.addListener(SWT.FocusIn, e -> page.tableFocused(this));
        Menu menu = new Menu(grid);
        menu.addListener(SWT.Show, e -> fillCellMenu(menu));
        grid.setMenu(menu);
        grid.addListener(SWT.Dispose, e -> disposeLayouts());
        QuickHelp.provider(grid, (c, p) -> describe(p));
        computeColumns();
        place();
    }

    public TableData data() { return data; }
    public NoteText editor() { return editor != null && !editor.isDisposed() ? editor : null; }
    public boolean hasSelection() { return fr >= 0; }

    @Override
    double minWidth() { return 60; }

    @Override
    Control content() { return grid; }

    @Override
    double contentHeight() {
        computeRows();
        double h = 0;
        for (double v : rowH) h += v;
        return Math.max(MIN_ROW, h) + 1;
    }

    @Override
    boolean isEditing() { return editor() != null || hasSelection(); }

    @Override
    PageElement snapshot() {
        if (editor() != null && editor.edited) storeCell(er, ec, editor.getRichText());
        element.table = data;
        return element;
    }

    @Override
    String indexText() {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : data.rows) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.join(" ", row));
        }
        return sb.toString();
    }

    private static long key(int r, int c) { return ((long) r << 20) | c; }

    // MARK: Geometry

    private void computeColumns() {
        int n = data.columnCount();
        colW = new double[n];
        double total = 0;
        for (int c = 0; c < n; c++) if (!data.isColumnHidden(c)) total += data.columnWidths.get(c);
        for (int c = 0; c < n; c++) {
            colW[c] = data.isColumnHidden(c) || total <= 0 ? 0 : Math.max(30, element.width * data.columnWidths.get(c) / total);
        }
    }

    private void computeRows() {
        int rows = data.rowCount();
        rowH = new double[rows];
        for (int r = 0; r < rows; r++) {
            if (data.isRowHidden(r)) continue;
            double override = data.rowHeightOverride(r);
            double h = MIN_ROW;
            for (int c = 0; c < data.columnCount(); c++) {
                if (data.isCovered(r, c) || colW[c] == 0) continue;
                int[] span = data.span(r, c);
                if (span != null && span[2] > 1) continue;
                double w = spanWidth(r, c);
                double th = r == er && c == ec && editor() != null ? editorHeight() : textHeight(r, c, w);
                h = Math.max(h, th + 2 * PAD_Y);
            }
            rowH[r] = override > 0 && !(r == er && editor() != null) ? override : h;
        }
    }

    private double spanWidth(int r, int c) {
        int[] span = data.span(r, c);
        if (span == null) return colW[c];
        double w = 0;
        for (int k = span[1]; k < span[1] + span[3] && k < colW.length; k++) w += colW[k];
        return w;
    }

    private double spanHeight(int r, int c) {
        int[] span = data.span(r, c);
        if (span == null) return rowH[r];
        double h = 0;
        for (int k = span[0]; k < span[0] + span[2] && k < rowH.length; k++) h += rowH[k];
        return h;
    }

    private double colX(int c) {
        double x = 0;
        for (int k = 0; k < c; k++) x += colW[k];
        return x;
    }

    private double rowY(int r) {
        double y = 0;
        for (int k = 0; k < r; k++) y += rowH[k];
        return y;
    }

    private Rectangle cellRect(int r, int c) {
        return new Rectangle(px(colX(c)), px(rowY(r)), px(spanWidth(r, c)), px(spanHeight(r, c)));
    }

    /** The cell at a point of the grid (in pixels), or null. */
    private int[] cellAt(int x, int y) {
        double px = x / page.zoom, py = y / page.zoom;
        int r = -1, c = -1;
        double acc = 0;
        for (int k = 0; k < rowH.length; k++) {
            if (rowH[k] == 0) continue;
            if (py >= acc && py < acc + rowH[k]) r = k;
            acc += rowH[k];
        }
        if (py >= acc && rowH.length > 0) r = lastShown(rowH);
        acc = 0;
        for (int k = 0; k < colW.length; k++) {
            if (colW[k] == 0) continue;
            if (px >= acc && px < acc + colW[k]) c = k;
            acc += colW[k];
        }
        if (px >= acc && colW.length > 0) c = lastShown(colW);
        if (px < 0) c = firstShown(colW);
        if (py < 0) r = firstShown(rowH);
        if (r < 0 || c < 0) return null;
        return data.master(r, c);
    }

    private static int lastShown(double[] a) {
        for (int k = a.length - 1; k >= 0; k--) if (a[k] > 0) return k;
        return a.length - 1;
    }

    private static int firstShown(double[] a) {
        for (int k = 0; k < a.length; k++) if (a[k] > 0) return k;
        return 0;
    }

    /** The column whose right border is near the point (pixels), or -1. */
    private int borderAt(int x) {
        double acc = 0;
        for (int c = 0; c < colW.length; c++) {
            acc += colW[c];
            if (colW[c] > 0 && Math.abs(x - px(acc)) <= 3) return c;
        }
        return -1;
    }

    /** Lays the table out again after its data changed. */
    void relayout() {
        disposeLayouts();
        richCache.clear();
        values.invalidate();
        computeColumns();
        double oldBottom = element.y + element.height;
        place();
        placeEditor();
        grid.redraw();
        page.grew(this, oldBottom);
    }

    private void disposeLayouts() {
        layouts.values().forEach(TextLayout::dispose);
        layouts.clear();
    }

    // MARK: Cell text

    /** The table's font for a cell: its own, or the notebook's (bold in the header row). */
    CharStyle baseStyle(int r, int c) {
        CharStyle s = CharStyle.DEFAULT.withBold(data.headerRow && r == 0);
        Object[] own = Fonts.parseCellFont(data.cellFont(r, c));
        if (own != null) {
            Fonts.Face f = (Fonts.Face) own[0];
            s = s.withFont(f.family()).withSize((double) own[1]).withBold(f.bold() || s.bold()).withItalic(f.italic());
        }
        return s;
    }

    private RGB autoColor(int r, int c) { return data.textIsLight(r, c) ? new RGB(255, 255, 255) : Gfx.BLACK; }

    /** A cell's formatted text as stored (rich, else plain in the table's font). */
    RText cellContent(int r, int c) {
        byte[] rich = data.richData(r, c);
        if (rich != null) {
            return richCache.computeIfAbsent(key(r, c), k -> RichCodec.decode(rich));
        }
        RText plain = RText.plain(data.text(r, c), baseStyle(r, c));
        String a = data.cellAlign(r, c);
        if (a != null) plain = new RText(plain.text, plain.spans, List.of(ParaStyle.DEFAULT.withAlign(ParaStyle.alignOf(a))));
        return plain;
    }

    /** What the cell shows (a formula's result, a formatted number) with its formatting. */
    private RText displayContent(int r, int c) {
        RText stored = cellContent(r, c);
        String raw = data.text(r, c);
        String shown = values.display(r, c);
        if (shown.equals(raw)) return stored;
        CharStyle s = stored.spans.isEmpty() ? baseStyle(r, c) : stored.spans.get(0).style();
        return new RText(shown, shown.isEmpty() ? List.of() : List.of(new RText.Span(0, shown.length(), s)), stored.paras);
    }

    private TextLayout layout(int r, int c, double width) {
        long k = key(r, c);
        TextLayout l = layouts.get(k);
        int w = Math.max(1, px(width - 2 * PAD_X));
        if (l != null && l.getWidth() == w) return l;
        if (l != null) l.dispose();
        l = new TextLayout(getDisplay());
        RText rt = displayContent(r, c);
        l.setText(rt.text.replace(RText.LINE_BREAK, '\n'));
        l.setWidth(w);
        l.setTabs(new int[] { px(28) });
        RGB auto = autoColor(r, c);
        for (RText.Span s : rt.spans) {
            CharStyle cs = s.style();
            TextStyle ts = new TextStyle(Gfx.font(cs, page.zoom), Gfx.color(cs.link() != null ? Gfx.LINK : cs.color() == null ? auto : cs.color()),
                    Gfx.color(cs.background()));
            ts.underline = cs.underline() || cs.link() != null;
            ts.strikeout = cs.strike();
            if (cs.script() > 0) ts.rise = (int) Math.round(cs.size() * 0.33 * page.zoom);
            else if (cs.script() < 0) ts.rise = -(int) Math.round(cs.size() * 0.2 * page.zoom);
            l.setStyle(ts, s.start(), s.end() - 1);
        }
        if (rt.spans.isEmpty()) l.setFont(Gfx.font(baseStyle(r, c), page.zoom));
        String a = data.cellAlign(r, c);
        int align = a != null ? ParaStyle.alignOf(a) : rt.paras.isEmpty() ? ParaStyle.LEFT : rt.paras.get(0).align();
        l.setAlignment(align == ParaStyle.CENTER ? SWT.CENTER : align == ParaStyle.RIGHT ? SWT.RIGHT : SWT.LEFT);
        l.setJustify(align == ParaStyle.JUSTIFIED);
        layouts.put(k, l);
        return l;
    }

    private double textHeight(int r, int c, double width) {
        if (data.text(r, c).isEmpty() && data.richData(r, c) == null) {
            return baseStyle(r, c).size() * 1.25;
        }
        return layout(r, c, width).getBounds().height / page.zoom;
    }

    private double editorHeight() {
        int w = Math.max(1, px(spanWidth(er, ec)) - 1);
        return Math.max(MIN_ROW - 2 * PAD_Y, editor.computeSize(w, SWT.DEFAULT).y / page.zoom - 2 * PAD_Y);
    }

    // MARK: Drawing

    private void paint(GC gc) {
        gc.setAntialias(SWT.ON);
        Rectangle client = grid.getClientArea();

        boolean borders = !Boolean.TRUE.equals(data.hideBorders);
        for (int r = 0; r < data.rowCount(); r++) {
            if (rowH[r] == 0) continue;
            for (int c = 0; c < data.columnCount(); c++) {
                if (colW[c] == 0 || data.isCovered(r, c)) continue;
                Rectangle rect = cellRect(r, c);
                double[] shade = data.shade(r, c);
                if (shade != null) {
                    gc.setBackground(Gfx.color(Gfx.rgb(shade)));
                    gc.fillRectangle(rect);
                }
                if (!(r == er && c == ec && editor() != null)) {
                    TextLayout l = layout(r, c, spanWidth(r, c));
                    gc.setClipping(rect);
                    l.draw(gc, rect.x + px(PAD_X), rect.y + px(PAD_Y));
                    gc.setClipping((Rectangle) null);
                }
                if (isSelected(r, c)) {
                    gc.setAlpha(90);
                    gc.setBackground(Gfx.color(Gfx.CELL_SELECTION.red - 60, Gfx.CELL_SELECTION.green - 60, 255));
                    gc.fillRectangle(rect);
                    gc.setAlpha(255);
                }
                if (borders) {
                    gc.setForeground(Gfx.color(Gfx.TABLE_BORDER));
                    gc.drawRectangle(rect.x, rect.y, rect.width, rect.height);
                } else if (framed()) {
                    gc.setForeground(Gfx.color(225, 228, 233));
                    gc.drawRectangle(rect.x, rect.y, rect.width, rect.height);
                }
            }
        }
        if (fr >= 0) {
            Rectangle f = cellRect(fr, fc);
            gc.setForeground(Gfx.color(Gfx.SELECTED));
            gc.setLineWidth(2);
            gc.drawRectangle(f.x + 1, f.y + 1, f.width - 1, f.height - 1);
            gc.setLineWidth(1);
        }
    }

    private boolean isSelected(int r, int c) {
        if (fr < 0) return false;
        if (extra.contains(key(r, c))) return true;
        return r >= Math.min(ar, fr) && r <= Math.max(ar, fr) && c >= Math.min(ac, fc) && c <= Math.max(ac, fc);
    }

    /** The selected cells (the visible ones; merged blocks by their top-left cell), in reading order. */
    List<int[]> selectedCells() {
        List<int[]> out = new ArrayList<>();
        if (fr < 0) {
            if (er >= 0) out.add(new int[] { er, ec });
            return out;
        }
        for (int r = 0; r < data.rowCount(); r++) {
            for (int c = 0; c < data.columnCount(); c++) {
                if (isSelected(r, c) && !data.isCovered(r, c) && !data.isRowHidden(r) && !data.isColumnHidden(c)) out.add(new int[] { r, c });
            }
        }
        return out;
    }

    private int[] selectedRows() {
        List<int[]> cells = selectedCells();
        if (cells.isEmpty()) return new int[0];
        return cells.stream().mapToInt(x -> x[0]).distinct().sorted().toArray();
    }

    private int[] selectedColumns() {
        List<int[]> cells = selectedCells();
        if (cells.isEmpty()) return new int[0];
        return cells.stream().mapToInt(x -> x[1]).distinct().sorted().toArray();
    }

    // MARK: Editing a cell

    /** Starts typing in a cell (the caret at the end). */
    public void beginEditing(int r, int c) {
        beginEditing(r, c, null, null);
    }

    private void beginEditing(int r, int c, Point click, String replaceWith) {
        if (r < 0 || c < 0 || r >= data.rowCount() || c >= data.columnCount()) return;
        int[] m = data.master(r, c);
        r = m[0];
        c = m[1];
        if (er == r && ec == c && editor() != null) {
            if (click != null) placeCaret(click);
            return;
        }
        endEditing();
        clearSelection();
        er = r;
        ec = c;
        editBefore = data.copy();
        editWidthBefore = element.width;
        editor = new NoteText(grid, SWT.NONE, page.zoom);
        editor.setMargins(px(PAD_X), px(PAD_Y), px(PAD_X), px(PAD_Y));
        double[] shade = data.shade(r, c);
        editor.setBackground(shade != null ? Gfx.color(Gfx.rgb(shade)) : page.paperColor());
        editor.setAutoColor(autoColor(r, c));
        editor.setBase(baseStyle(r, c));
        editor.setRichText(cellContent(r, c));
        editor.setHost(this);
        Menu menu = new Menu(editor);
        menu.addListener(SWT.Show, e -> fillCellMenu(menu));
        editor.setMenu(menu);
        editor.addListener(SWT.MouseMove, this::editorDrag);
        editor.addListener(SWT.MouseUp, e -> dragStart = null);
        editor.addListener(SWT.MouseDown, e -> {
            if (e.button == 1) dragStart = new int[] { er, ec };
        });
        QuickHelp.provider(editor, (ctl, p) -> new QuickHelpItem("Cell " + Formula.columnName(ec) + (er + 1),
                cellDetail(er, ec) + "Type in the cell; Tab goes to the next cell, Return to the row below (and adds a row at the end), Alt+Return starts a new line, Escape selects the cell."));
        computeRows();
        double oldBottom = element.y + element.height;
        place();
        placeEditor();
        page.grew(this, oldBottom);
        editor.setFocus();
        if (replaceWith != null) {
            editor.selectAll();
            editor.insertTyped(replaceWith);
        } else if (click != null) {
            placeCaret(click);
        } else {
            editor.setSelection(editor.getCharCount());
        }
        page.tableFocused(this);
        grid.redraw();
    }

    private void placeCaret(Point click) {
        Point p = editor.toControl(grid.toDisplay(click));
        try {
            int off = editor.getOffsetAtPoint(p);
            editor.setSelection(off >= 0 ? off : editor.getCharCount());
        } catch (RuntimeException ex) {
            editor.setSelection(editor.getCharCount());
        }
    }

    /** Puts the editor away without storing it (its cell is going away); disposed after the key that did it. */
    private void dropEditor() {
        NoteText ed = editor;
        editor = null;
        er = ec = -1;
        editBefore = null;
        if (ed != null) {
            ed.setVisible(false);
            getDisplay().asyncExec(() -> {
                if (!ed.isDisposed()) ed.dispose();
            });
        }
    }

    private void placeEditor() {
        if (editor() == null || er < 0 || ec < 0 || er >= data.rowCount() || ec >= data.columnCount()) return;
        Rectangle rect = cellRect(er, ec);
        editor.setBounds(rect.x + 1, rect.y + 1, Math.max(1, rect.width - 1), Math.max(1, rect.height - 1));
    }

    /** Stores what was typed and puts the editor away. */
    void endEditing() {
        if (editor() == null) {
            er = ec = -1;
            return;
        }
        NoteText ed = editor;
        int r = er, c = ec;
        editor = null;
        er = ec = -1;
        if (ed.edited) storeCell(r, c, ed.getRichText());
        boolean changed = editBefore != null && !editBefore.toJson().equals(data.toJson());
        if (changed) page.tableWillChange(this, "Typing", editBefore, editWidthBefore);
        editBefore = null;
        // The editor may be in the middle of handling the key that moved on: it goes once that's done.
        ed.setVisible(false);
        getDisplay().asyncExec(() -> {
            if (!ed.isDisposed()) ed.dispose();
        });
        relayout();
        if (changed) page.contentChanged();
    }

    /** Saves edited cell text: rich text only when there is formatting; the cell remembers its alignment and font. */
    void storeCell(int r, int c, RText rt) {
        if (r < 0 || c < 0 || r >= data.rowCount() || c >= data.columnCount()) return;
        data.setText(r, c, rt.text);
        if (!rt.text.isEmpty()) {
            ParaStyle p = rt.paras.isEmpty() ? ParaStyle.DEFAULT : rt.paras.get(0);
            data.setCellAlign(r, c, p.alignName());
            CharStyle first = rt.spans.isEmpty() ? baseStyle(r, c) : rt.spans.get(0).style();
            CharStyle def = CharStyle.DEFAULT.withBold(data.headerRow && r == 0);
            boolean same = first.font().equals(def.font()) && Math.abs(first.size() - def.size()) < 0.01;
            data.setCellFont(r, c, same ? null : Fonts.savedCellFont(first.font(), first.size(), first.bold(), first.italic()));
        }
        data.setRich(r, c, isPlain(rt, data.headerRow && r == 0) ? null : RichCodec.encode(rt));
        richCache.remove(key(r, c));
        values.invalidate();
        if (r == addedRow && !rt.text.isEmpty()) addedRow = -1;
        if (c == addedColumn && !rt.text.isEmpty()) addedColumn = -1;
    }

    /** True when the text has no formatting beyond the table's default font. */
    static boolean isPlain(RText rt, boolean header) {
        for (RText.Span s : rt.spans) {
            CharStyle cs = s.style();
            if (!cs.font().equals(CharStyle.DEFAULT_FONT) || Math.abs(cs.size() - CharStyle.DEFAULT_SIZE) > 0.01 || cs.bold() != header
                    || cs.italic() || cs.underline() || cs.strike() || cs.color() != null || cs.background() != null || cs.script() != 0
                    || cs.link() != null || cs.attachment() != null || !cs.extras().isEmpty()) return false;
        }
        for (ParaStyle p : rt.paras) if (p.align() != ParaStyle.LEFT || p.firstIndent() != 0 || p.headIndent() != 0) return false;
        return true;
    }

    /** Moves the typing to another cell, as Tab, Return and the arrows do. */
    private void moveTo(int r, int c, boolean atStart) {
        endEditing();
        beginEditing(r, c);
        if (atStart && editor() != null) editor.setSelection(0);
    }

    private int nextColumn(int r, int c) {
        for (int k = c + 1; k < data.columnCount(); k++) if (!data.isColumnHidden(k) && !data.isCovered(r, k)) return k;
        return -1;
    }

    private int prevColumn(int r, int c) {
        for (int k = c - 1; k >= 0; k--) if (!data.isColumnHidden(k) && !data.isCovered(r, k)) return k;
        return -1;
    }

    private int nextRow(int r) {
        for (int k = r + 1; k < data.rowCount(); k++) if (!data.isRowHidden(k)) return k;
        return -1;
    }

    private int prevRow(int r) {
        for (int k = r - 1; k >= 0; k--) if (!data.isRowHidden(k)) return k;
        return -1;
    }

    // MARK: NoteText.Host (the cell editor)

    @Override
    public boolean keyFirst(NoteText t, int key, int mask, char ch) {
        boolean shift = (mask & SWT.SHIFT) != 0, ctrl = (mask & SWT.CTRL) != 0, alt = (mask & SWT.ALT) != 0;
        int r = er, c = ec;
        switch (key) {
            case SWT.TAB -> {
                if (ctrl) return false;
                if (shift) {
                    int pc = prevColumn(r, c);
                    if (pc >= 0) moveTo(r, pc, false);
                    else if (prevRow(r) >= 0) moveTo(prevRow(r), lastColumn(prevRow(r)), false);
                    return true;
                }
                int nc = nextColumn(r, c);
                boolean filled = !editor.getText().isEmpty();
                if (data.rowCount() == 1 && nc < 0 && filled) {
                    // While the table has one row, Tab in a filled last cell adds a column.
                    change("Insert Column", () -> {
                        storeCell(r, c, editor.getRichText());
                        editor.edited = false;
                        double w = colW[c] > 0 ? colW[c] : 120;
                        data.insertColumn(c + 1);
                        data.columnWidths.set(c + 1, w);
                        element.width += w;
                    });
                    addedColumn = c + 1;
                    moveTo(r, c + 1, false);
                    return true;
                }
                if (nc >= 0) {
                    moveTo(r, nc, false);
                } else if (nextRow(r) >= 0) {
                    moveTo(nextRow(r), firstColumn(nextRow(r)), false);
                } else {
                    addRowBelow(r);
                    moveTo(r + 1, firstColumn(r + 1), false);
                }
                return true;
            }
            case SWT.CR, SWT.KEYPAD_CR -> {
                if (alt || shift) {
                    t.insertTyped(alt ? "\n" : String.valueOf('\r'));
                    return true;
                }
                if (ctrl) {
                    addRowBelow(r);
                    moveTo(r + 1, c, false);
                    return true;
                }
                if (Formula.isFormula(editor.getText()) || nextRow(r) >= 0) {
                    int nr = nextRow(r);
                    if (nr < 0) {
                        addRowBelow(r);
                        nr = r + 1;
                    }
                    moveTo(nr, c, false);
                    return true;
                }
                addRowBelow(r);
                moveTo(r + 1, c, false);
                return true;
            }
            case SWT.BS -> {
                if (!editor.getText().isEmpty() || editor.getSelectionCount() > 0) return false;
                if (c == addedColumn && columnEmpty(c) && data.columnCount() > 1) {
                    dropEditor();
                    change("Delete Column", () -> {
                        element.width -= colW[c];
                        data.removeColumn(c);
                    });
                    addedColumn = -1;
                    beginEditing(r, Math.max(0, c - 1));
                    return true;
                }
                if (r == addedRow && rowEmpty(r) && data.rowCount() > 1) {
                    dropEditor();
                    change("Delete Row", () -> data.removeRow(r));
                    addedRow = -1;
                    beginEditing(Math.max(0, r - 1), c);
                    return true;
                }
                return false;
            }
            case SWT.ESC -> {
                endEditing();
                select(r, c, false);
                grid.setFocus();
                return true;
            }
            case SWT.ARROW_UP, SWT.ARROW_DOWN -> {
                boolean up = key == SWT.ARROW_UP;
                if (alt && shift) {
                    moveRows(up);
                    return true;
                }
                if (mask != 0 || !t.caretOnEdgeLine(up)) return false;
                int nr = up ? prevRow(r) : nextRow(r);
                if (nr >= 0) {
                    moveTo(nr, c, false);
                    return true;
                }
                endEditing();
                return page.moveFocus(this, up, element.x + colX(c));
            }
            case SWT.ARROW_LEFT -> {
                if (mask != 0 || t.getCaretOffset() > 0 || t.getSelectionCount() > 0) return false;
                int pc = prevColumn(r, c);
                if (pc >= 0) moveTo(r, pc, false);
                return pc >= 0;
            }
            case SWT.ARROW_RIGHT -> {
                if (mask != 0 || t.getCaretOffset() < t.getCharCount() || t.getSelectionCount() > 0) return false;
                int nc = nextColumn(r, c);
                if (nc >= 0) moveTo(r, nc, true);
                return nc >= 0;
            }
            default -> {
                return false;
            }
        }
    }

    private int firstColumn(int r) {
        for (int k = 0; k < data.columnCount(); k++) if (!data.isColumnHidden(k) && !data.isCovered(r, k)) return k;
        return 0;
    }

    private int lastColumn(int r) {
        for (int k = data.columnCount() - 1; k >= 0; k--) if (!data.isColumnHidden(k) && !data.isCovered(r, k)) return k;
        return 0;
    }

    private boolean columnEmpty(int c) {
        for (int r = 0; r < data.rowCount(); r++) if (!data.text(r, c).isEmpty() && !(r == er && c == ec)) return false;
        return true;
    }

    private boolean rowEmpty(int r) {
        for (int c = 0; c < data.columnCount(); c++) if (!data.text(r, c).isEmpty() && !(r == er && c == ec)) return false;
        return true;
    }

    private void addRowBelow(int r) {
        change("Insert Row", () -> {
            if (editor() != null && editor.edited) {
                storeCell(er, ec, editor.getRichText());
                editor.edited = false;
            }
            data.insertRow(r + 1);
        });
        addedRow = r + 1;
    }

    @Override
    public void willChange(NoteText t, String action, boolean typing) {}

    @Override
    public void changed(NoteText t) {
        if (editor() == null) return;
        double old = rowH.length > er && er >= 0 ? rowH[er] : 0;
        computeRows();
        if (er >= 0 && er < rowH.length && Math.abs(rowH[er] - old) > 0.5) {
            double oldBottom = element.y + element.height;
            place();
            placeEditor();
            grid.redraw();
            page.grew(this, oldBottom);
        }
        page.contentChanged();
    }

    @Override
    public void escape(NoteText t) {
        keyFirst(t, SWT.ESC, 0, (char) 27);
    }

    @Override
    public void openLink(String url) { page.openLink(url); }

    @Override
    public void focused(NoteText t) { page.tableFocused(this); }

    @Override
    public void selectionChanged(NoteText t) { page.formattingChanged(); }

    @Override
    public String pageLink(String title) { return page.store.linkForPage(title); }

    // MARK: Selecting cells

    private void clearSelection() {
        ar = ac = fr = fc = -1;
        extra.clear();
    }

    void select(int r, int c, boolean extend) {
        if (!extend || fr < 0) {
            ar = r;
            ac = c;
            extra.clear();
        }
        fr = r;
        fc = c;
        grid.redraw();
        page.tableFocused(this);
        page.formattingChanged();
    }

    public void selectAll() {
        endEditing();
        ar = 0;
        ac = 0;
        fr = data.rowCount() - 1;
        fc = data.columnCount() - 1;
        extra.clear();
        grid.setFocus();
        grid.redraw();
        page.formattingChanged();
    }

    private void mouseDown(Event e) {
        if (e.button != 1) return;
        page.select(null);
        int border = borderAt(e.x);
        if (border >= 0 && e.count == 1) {
            endEditing();
            resizeColumn = border;
            resizeStartX = getDisplay().getCursorLocation().x;
            resizeWidths = colW.clone();
            resizeBefore = data.copy();
            editWidthBefore = element.width;
            return;
        }
        int[] cell = cellAt(e.x, e.y);
        if (cell == null) return;
        if ((e.stateMask & SWT.SHIFT) != 0) {
            if (fr < 0 && er >= 0) {
                ar = er;
                ac = ec;
            }
            endEditing();
            select(cell[0], cell[1], true);
            grid.setFocus();
            return;
        }
        if ((e.stateMask & SWT.CTRL) != 0) {
            if (fr < 0 && er >= 0) {
                ar = fr = er;
                ac = fc = ec;
            }
            endEditing();
            long k = key(cell[0], cell[1]);
            if (!extra.remove(k)) extra.add(k);
            fr = cell[0];
            fc = cell[1];
            if (ar < 0) {
                ar = fr;
                ac = fc;
            }
            grid.setFocus();
            grid.redraw();
            return;
        }
        dragStart = cell;
        beginEditing(cell[0], cell[1], new Point(e.x, e.y), null);
    }

    private void mouseMove(Event e) {
        if (resizeColumn >= 0) {
            double dx = (getDisplay().getCursorLocation().x - resizeStartX) / page.zoom;
            double w = Math.max(30, resizeWidths[resizeColumn] + dx);
            setAbsoluteWidths(resizeWidths, resizeColumn, w);
            relayout();
            return;
        }
        grid.setCursor(borderAt(e.x) >= 0 ? getDisplay().getSystemCursor(SWT.CURSOR_SIZEWE) : null);
        if ((e.stateMask & SWT.BUTTON1) == 0 || dragStart == null) return;
        int[] cell = cellAt(e.x, e.y);
        if (cell == null || cell[0] == dragStart[0] && cell[1] == dragStart[1] && fr < 0) return;
        if (editor() != null) {
            endEditing();
            ar = dragStart[0];
            ac = dragStart[1];
            grid.setFocus();
        }
        select(cell[0], cell[1], true);
    }

    private void editorDrag(Event e) {
        if ((e.stateMask & SWT.BUTTON1) == 0 || dragStart == null || editor() == null) return;
        Point p = grid.toControl(editor.toDisplay(e.x, e.y));
        int[] cell = cellAt(p.x, p.y);
        if (cell == null || cell[0] == er && cell[1] == ec) return;
        int[] start = { er, ec };
        endEditing();
        ar = start[0];
        ac = start[1];
        dragStart = start;
        grid.setFocus();
        select(cell[0], cell[1], true);
    }

    private void mouseUp() {
        if (resizeColumn >= 0) {
            resizeColumn = -1;
            if (!resizeBefore.toJson().equals(data.toJson())) page.tableWillChange(this, "Column Width", resizeBefore, editWidthBefore);
            page.contentChanged();
        }
        dragStart = null;
    }

    private void doubleClick(Event e) {
        int border = borderAt(e.x);
        if (border < 0) return;
        resizeColumn = -1;
        int[] cols = selectedColumns();
        boolean whole = cols.length > 1 && Arrays.stream(cols).anyMatch(c -> c == border);
        change("Auto-Fit Column", () -> {
            if (whole) for (int c : cols) autoFit(c);
            else autoFit(border);
        });
    }

    /** Sets one column's width (points); the table grows or shrinks with it. */
    private void setAbsoluteWidths(double[] widths, int column, double w) {
        double[] abs = widths.clone();
        abs[column] = w;
        double total = 0;
        for (int c = 0; c < abs.length; c++) {
            if (!data.isColumnHidden(c)) {
                data.columnWidths.set(c, abs[c]);
                total += abs[c];
            }
        }
        element.width = total;
    }

    /** Fits a column to its widest shown cell on one line. */
    private void autoFit(int column) {
        double widest = 30;
        TextLayout l = new TextLayout(getDisplay());
        try {
            for (int r = 0; r < data.rowCount(); r++) {
                if (data.isRowHidden(r) || data.isCovered(r, column)) continue;
                int[] span = data.span(r, column);
                if (span != null && span[3] > 1) continue;
                RText rt = displayContent(r, column);
                l.setText(rt.text.replace(RText.LINE_BREAK, '\n'));
                l.setWidth(-1);
                for (RText.Span s : rt.spans) l.setStyle(new TextStyle(Gfx.font(s.style(), 1), null, null), s.start(), s.end() - 1);
                if (rt.spans.isEmpty()) l.setFont(Gfx.font(baseStyle(r, column), 1));
                widest = Math.max(widest, l.getBounds().width + 2 * PAD_X + 4);
            }
        } finally {
            l.dispose();
        }
        computeColumns();
        setAbsoluteWidths(colW, column, Math.ceil(widest));
    }

    private void gridKey(Event e) {
        if (fr < 0) return;
        boolean shift = (e.stateMask & SWT.SHIFT) != 0, ctrl = (e.stateMask & SWT.CTRL) != 0;
        switch (e.keyCode) {
            case SWT.ESC -> {
                clearSelection();
                grid.redraw();
                page.select(this);
                return;
            }
            case SWT.DEL, SWT.BS -> {
                clearCells();
                return;
            }
            case SWT.CR, SWT.KEYPAD_CR, SWT.F2 -> {
                beginEditing(fr, fc);
                return;
            }
            case SWT.TAB -> {
                int nc = shift ? prevColumn(fr, fc) : nextColumn(fr, fc);
                if (nc >= 0) select(fr, nc, false);
                return;
            }
            case SWT.ARROW_UP, SWT.ARROW_DOWN, SWT.ARROW_LEFT, SWT.ARROW_RIGHT -> {
                int r = fr, c = fc;
                if (e.keyCode == SWT.ARROW_UP) r = Math.max(0, prevRow(fr) < 0 ? fr : prevRow(fr));
                if (e.keyCode == SWT.ARROW_DOWN) r = nextRow(fr) < 0 ? fr : nextRow(fr);
                if (e.keyCode == SWT.ARROW_LEFT) c = prevColumn(fr, fc) < 0 ? fc : prevColumn(fr, fc);
                if (e.keyCode == SWT.ARROW_RIGHT) c = nextColumn(fr, fc) < 0 ? fc : nextColumn(fr, fc);
                if ((e.stateMask & SWT.ALT) != 0 && shift && (e.keyCode == SWT.ARROW_UP || e.keyCode == SWT.ARROW_DOWN)) {
                    moveRows(e.keyCode == SWT.ARROW_UP);
                    return;
                }
                select(r, c, shift);
                return;
            }
            default -> {}
        }
        if (ctrl && (e.keyCode == 'a' || e.keyCode == 'A')) {
            selectAll();
            return;
        }
        if (!ctrl && (e.stateMask & SWT.ALT) == 0 && e.character >= 32 && e.character != 127) {
            beginEditing(fr, fc, null, String.valueOf(e.character));
        }
    }

    // MARK: Changes (each one undoable)

    /** Makes a change to the table as one Undo step. */
    void change(String name, Runnable op) {
        TableData before = data.copy();
        double widthBefore = element.width;
        if (editor() != null && editor.edited) {
            storeCell(er, ec, editor.getRichText());
            editor.edited = false;
            if (editBefore != null) {
                before = editBefore;
                widthBefore = editWidthBefore;
                editBefore = data.copy();
            }
        }
        op.run();
        data.normalize();
        page.tableWillChange(this, name, before, widthBefore);
        if (editBefore != null) {
            editBefore = data.copy();
            editWidthBefore = element.width;
        }
        relayout();
        page.contentChanged();
    }

    /** Puts the table back as it was (Undo / Redo). */
    void restore(TableData before, double width) {
        if (editor() != null) {
            editor.dispose();
            editor = null;
            er = ec = -1;
        }
        clearSelection();
        data = before.copy();
        element.table = data;
        element.width = width;
        values = new TableValues(data);
        relayout();
        page.contentChanged();
    }

    private int[] targetRows() {
        int[] rows = selectedRows();
        return rows.length > 0 ? rows : er >= 0 ? new int[] { er } : new int[0];
    }

    private int[] targetColumns() {
        int[] cols = selectedColumns();
        return cols.length > 0 ? cols : ec >= 0 ? new int[] { ec } : new int[0];
    }

    public void insertRows(boolean below) {
        int[] rows = targetRows();
        if (rows.length == 0) return;
        int at = below ? rows[rows.length - 1] + 1 : rows[0];
        endEditing();
        change(rows.length > 1 ? "Insert " + rows.length + " Rows" : "Insert Row", () -> {
            for (int i = 0; i < rows.length; i++) data.insertRow(at);
        });
        ar = at;
        fr = at + rows.length - 1;
        ac = 0;
        fc = data.columnCount() - 1;
        grid.redraw();
    }

    public void insertColumns(boolean right) {
        int[] cols = targetColumns();
        if (cols.length == 0) return;
        int at = right ? cols[cols.length - 1] + 1 : cols[0];
        endEditing();
        change(cols.length > 1 ? "Insert " + cols.length + " Columns" : "Insert Column", () -> {
            for (int i = 0; i < cols.length; i++) {
                int neighbor = Math.max(0, Math.min(data.columnCount() - 1, right ? at - 1 : at));
                double w = data.columnWidths.isEmpty() ? 120 : colW.length > neighbor && colW[neighbor] > 0 ? colW[neighbor] : 120;
                data.insertColumn(at);
                data.columnWidths.set(at, w);
                element.width += w;
            }
        });
        ar = 0;
        fr = data.rowCount() - 1;
        ac = at;
        fc = at + cols.length - 1;
        grid.redraw();
    }

    public void deleteRows() {
        int[] rows = targetRows();
        if (rows.length == 0) return;
        if (rows.length >= data.rowCount()) {
            deleteTable();
            return;
        }
        endEditing();
        change(rows.length > 1 ? "Delete Rows" : "Delete Row", () -> {
            for (int i = rows.length - 1; i >= 0; i--) data.removeRow(rows[i]);
        });
        clearSelection();
        grid.redraw();
    }

    public void deleteColumns() {
        int[] cols = targetColumns();
        if (cols.length == 0) return;
        if (cols.length >= data.columnCount()) {
            deleteTable();
            return;
        }
        endEditing();
        change(cols.length > 1 ? "Delete Columns" : "Delete Column", () -> {
            for (int i = cols.length - 1; i >= 0; i--) {
                element.width -= colW[cols[i]];
                data.removeColumn(cols[i]);
            }
        });
        clearSelection();
        grid.redraw();
    }

    public void deleteTable() {
        endEditing();
        page.remove(this, true);
    }

    public void clearCells() {
        List<int[]> cells = selectedCells();
        if (cells.isEmpty()) return;
        change("Clear", () -> {
            for (int[] x : cells) {
                data.setText(x[0], x[1], "");
                data.setRich(x[0], x[1], null);
            }
        });
    }

    public void toggleHeaderRow() {
        endEditing();
        change("Header Row", () -> data.headerRow = !data.headerRow);
    }

    public void toggleBorders() {
        change("Hide Borders", () -> data.hideBorders = Boolean.TRUE.equals(data.hideBorders) ? null : Boolean.TRUE);
    }

    /** Shading for the selected cells (null: none). */
    public void shadeCells(RGB color) {
        List<int[]> cells = selectedCells();
        change("Shading", () -> {
            for (int[] x : cells) data.setShade(x[0], x[1], Gfx.rgba(color));
        });
    }

    public void shadeRows(RGB color) {
        int[] rows = targetRows();
        change("Row Shading", () -> {
            for (int r : rows) for (int c = 0; c < data.columnCount(); c++) data.setShade(r, c, Gfx.rgba(color));
        });
    }

    public void shadeColumns(RGB color) {
        int[] cols = targetColumns();
        change("Column Shading", () -> {
            for (int c : cols) for (int r = 0; r < data.rowCount(); r++) data.setShade(r, c, Gfx.rgba(color));
        });
    }

    public void shadeHeader(RGB color) {
        change("Header Row Shading", () -> {
            for (int c = 0; c < data.columnCount(); c++) data.setShade(0, c, Gfx.rgba(color));
        });
    }

    /** Banded rows: every other row (after the header) shaded. */
    public void shadeBanded(RGB color) {
        change("Banded Rows", () -> {
            int start = data.headerRow ? 1 : 0;
            for (int r = start; r < data.rowCount(); r++) {
                for (int c = 0; c < data.columnCount(); c++) data.setShade(r, c, (r - start) % 2 == 1 ? Gfx.rgba(color) : null);
            }
        });
    }

    public void shadeTable(RGB color) {
        change("Table Shading", () -> {
            for (int r = 0; r < data.rowCount(); r++) for (int c = 0; c < data.columnCount(); c++) data.setShade(r, c, Gfx.rgba(color));
        });
    }

    /** Formats the text of the selected cells (or the cell being typed in) — the Home ribbon with cells selected. */
    public void changeCells(String name, UnaryOperator<CharStyle> f) {
        if (editor() != null && fr < 0) {
            editor.changeChars(name, f);
            return;
        }
        List<int[]> cells = selectedCells();
        if (cells.isEmpty()) return;
        change(name, () -> {
            for (int[] x : cells) {
                int r = x[0], c = x[1];
                RText rt = cellContent(r, c);
                if (rt.text.isEmpty()) {
                    CharStyle s = f.apply(baseStyle(r, c));
                    CharStyle def = CharStyle.DEFAULT.withBold(data.headerRow && r == 0);
                    boolean same = s.font().equals(def.font()) && Math.abs(s.size() - def.size()) < 0.01;
                    data.setCellFont(r, c, same ? null : Fonts.savedCellFont(s.font(), s.size(), s.bold(), s.italic()));
                    continue;
                }
                storeCell(r, c, rt.mapStyles(f));
            }
        });
    }

    /** The formatting of the selected cells' text, for the ribbon. */
    public List<CharStyle> cellStyles() {
        List<CharStyle> out = new ArrayList<>();
        for (int[] x : selectedCells()) {
            RText rt = cellContent(x[0], x[1]);
            if (rt.spans.isEmpty()) out.add(baseStyle(x[0], x[1]));
            for (RText.Span s : rt.spans) out.add(s.style());
        }
        if (out.isEmpty()) out.add(CharStyle.DEFAULT);
        return out;
    }

    public void alignCells(int align) {
        if (editor() != null && fr < 0) {
            editor.changeParagraphs("Alignment", p -> p.withAlign(align));
            return;
        }
        List<int[]> cells = selectedCells();
        change("Alignment", () -> {
            for (int[] x : cells) {
                data.setCellAlign(x[0], x[1], ParaStyle.DEFAULT.withAlign(align).alignName());
                RText rt = cellContent(x[0], x[1]);
                if (data.richData(x[0], x[1]) != null) {
                    List<ParaStyle> ps = new ArrayList<>();
                    for (ParaStyle p : rt.paras) ps.add(p.withAlign(align));
                    storeCell(x[0], x[1], new RText(rt.text, rt.spans, ps));
                }
            }
        });
    }

    /** Sorts the rows by the selected column (numbers in order, case ignored, blanks last); the header stays first. */
    public void sort(boolean ascending) {
        int column = fc >= 0 ? fc : ec >= 0 ? ec : 0;
        endEditing();
        for (int[] m : data.merges == null ? List.<int[]>of() : data.merges) {
            if (m[2] > 1) {
                com.k3jdr.mx.core.ui.ErrorDialog.show(getShell(), "Can't sort the table", "A table with cells merged across rows can't be sorted.");
                return;
            }
        }
        boolean includeFirst = !data.headerRow && page.store.prefs().getBoolean("tableSortIncludesFirstRow", false);
        int start = data.headerRow || !includeFirst ? 1 : 0;
        if (data.rowCount() - start < 2) return;
        Integer[] order = new Integer[data.rowCount() - start];
        for (int i = 0; i < order.length; i++) order[i] = i + start;
        TableValues v = values;
        Arrays.sort(order, (a, b) -> {
            String ta = v.display(a, column).strip(), tb = v.display(b, column).strip();
            if (ta.isEmpty() != tb.isEmpty()) return ta.isEmpty() ? 1 : -1;
            if (ta.isEmpty()) return 0;
            Double na = v.sortNumber(a, column), nb = v.sortNumber(b, column);
            int cmp;
            if (na != null && nb != null) cmp = Double.compare(na, nb);
            else if (na != null) cmp = -1;
            else if (nb != null) cmp = 1;
            else cmp = ta.compareToIgnoreCase(tb);
            return ascending ? cmp : -cmp;
        });
        int[] full = new int[data.rowCount()];
        for (int i = 0; i < start; i++) full[i] = i;
        for (int i = 0; i < order.length; i++) full[i + start] = order[i];
        change(ascending ? "Sort A to Z" : "Sort Z to A", () -> {
            data.reorderRows(full);
            for (int newRow = 0; newRow < full.length; newRow++) {
                int oldRow = full[newRow];
                if (oldRow == newRow) continue;
                for (int c = 0; c < data.columnCount(); c++) {
                    String s = data.text(newRow, c);
                    if (Formula.isFormula(s) && !s.contains(":")) data.setText(newRow, c, Formula.offset(s, newRow - oldRow, 0));
                }
            }
        });
    }

    /** Moves the selected rows (or the row being typed in) up or down one row. */
    void moveRows(boolean up) {
        int[] rows = targetRows();
        if (rows.length == 0) return;
        int first = rows[0], last = rows[rows.length - 1];
        int headerTop = data.headerRow ? 1 : 0;
        if (up && first <= headerTop || !up && last >= data.rowCount() - 1) return;
        int[] order = new int[data.rowCount()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        if (up) {
            int moved = first - 1;
            for (int i = first - 1; i < last; i++) order[i] = i + 1;
            order[last] = moved;
        } else {
            int moved = last + 1;
            for (int i = last + 1; i > first; i--) order[i] = i - 1;
            order[first] = moved;
        }
        int er0 = er, ec0 = ec;
        endEditing();
        change(up ? "Move Row Up" : "Move Row Down", () -> data.reorderRows(order));
        int shift = up ? -1 : 1;
        if (er0 >= 0) beginEditing(er0 + shift, ec0);
        else {
            ar = first + shift;
            fr = last + shift;
            ac = 0;
            fc = data.columnCount() - 1;
            grid.redraw();
        }
    }

    public void mergeCells() {
        if (fr < 0) return;
        int r0 = Math.min(ar, fr), r1 = Math.max(ar, fr), c0 = Math.min(ac, fc), c1 = Math.max(ac, fc);
        if (r0 == r1 && c0 == c1) return;
        change("Merge Cells", () -> {
            List<String> texts = new ArrayList<>();
            for (int r = r0; r <= r1; r++) for (int c = c0; c <= c1; c++) if (!data.text(r, c).isBlank()) texts.add(data.text(r, c));
            List<int[]> merges = new ArrayList<>();
            if (data.merges != null) {
                for (int[] m : data.merges) {
                    boolean inside = m[0] >= r0 && m[0] + m[2] - 1 <= r1 && m[1] >= c0 && m[1] + m[3] - 1 <= c1;
                    if (!inside) merges.add(m);
                }
            }
            merges.add(new int[] { r0, c0, r1 - r0 + 1, c1 - c0 + 1 });
            data.merges = merges;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    if (r == r0 && c == c0) continue;
                    data.setText(r, c, "");
                    data.setRich(r, c, null);
                }
            }
            data.setText(r0, c0, String.join(" ", texts));
            data.setRich(r0, c0, null);
        });
        ar = fr = r0;
        ac = fc = c0;
        grid.redraw();
    }

    public void unmergeCells() {
        int r = fr >= 0 ? fr : er, c = fr >= 0 ? fc : ec;
        if (r < 0 || data.span(r, c) == null) return;
        int[] span = data.span(r, c);
        endEditing();
        change("Unmerge Cells", () -> data.merges.removeIf(m -> m == span));
    }

    /** AutoSum: sums the numbers just above the cell (else just left of it). */
    public void autoSum() {
        int r = fr >= 0 ? fr : er, c = fr >= 0 ? fc : ec;
        if (r < 0) return;
        int top = r;
        while (top - 1 >= 0 && values.sortNumber(top - 1, c) != null) top--;
        String formula;
        if (top < r) {
            formula = "=SUM(" + Formula.columnName(c) + (top + 1) + ":" + Formula.columnName(c) + r + ")";
        } else {
            int left = c;
            while (left - 1 >= 0 && values.sortNumber(r, left - 1) != null) left--;
            if (left == c) return;
            formula = "=SUM(" + Formula.columnName(left) + (r + 1) + ":" + Formula.columnName(c - 1) + (r + 1) + ")";
        }
        endEditing();
        String f = formula;
        change("AutoSum", () -> {
            data.setText(r, c, f);
            data.setRich(r, c, null);
        });
    }

    /** Formulas ▸ a function: typed into the cell with the caret inside its brackets. */
    public void insertFunction(String name) {
        int r = fr >= 0 ? fr : er, c = fr >= 0 ? fc : ec;
        if (r < 0) return;
        if (editor() == null) beginEditing(r, c, null, "");
        editor.selectAll();
        editor.insertTyped("=" + name + "()");
        editor.setSelection(editor.getCharCount() - 1);
    }

    // MARK: Clipboard (tab-separated cells)

    /** The selected block as tab-separated text (hidden rows and columns left out). */
    String selectedTsv() {
        List<int[]> cells = selectedCells();
        if (cells.isEmpty()) return "";
        int r0 = Integer.MAX_VALUE, r1 = -1, c0 = Integer.MAX_VALUE, c1 = -1;
        for (int[] x : cells) {
            r0 = Math.min(r0, x[0]);
            r1 = Math.max(r1, x[0]);
            c0 = Math.min(c0, x[1]);
            c1 = Math.max(c1, x[1]);
        }
        StringBuilder sb = new StringBuilder();
        for (int r = r0; r <= r1; r++) {
            if (data.isRowHidden(r)) continue;
            if (sb.length() > 0) sb.append("\r\n");
            boolean firstCol = true;
            for (int c = c0; c <= c1; c++) {
                if (data.isColumnHidden(c)) continue;
                if (!firstCol) sb.append('\t');
                firstCol = false;
                if (isSelected(r, c)) sb.append(tsvCell(values.display(r, c)));
            }
        }
        return sb.toString();
    }

    private static String tsvCell(String s) {
        String t = s.replace(RText.LINE_BREAK, '\n');
        if (t.contains("\t") || t.contains("\n") || t.contains("\"")) return "\"" + t.replace("\"", "\"\"") + "\"";
        return t;
    }

    public boolean canCopyCells() { return fr >= 0; }

    public void copyCells() {
        String tsv = selectedTsv();
        if (!tsv.isEmpty()) Clipboards.copyText(getDisplay(), tsv);
    }

    public void cutCells() {
        copyCells();
        clearCells();
    }

    /** Pastes tab-separated text from the selected cell (the table grows to fit; one value fills every selected cell). */
    public void pasteCells() {
        String text = Clipboards.text(getDisplay());
        if (text == null) return;
        List<List<String>> grid = parseTsv(text);
        if (grid.isEmpty()) return;
        int r0 = fr >= 0 ? Math.min(ar, fr) : Math.max(0, er), c0 = fr >= 0 ? Math.min(ac, fc) : Math.max(0, ec);
        endEditing();
        List<int[]> cells = selectedCells();
        change("Paste", () -> {
            if (grid.size() == 1 && grid.get(0).size() == 1 && cells.size() > 1) {
                for (int[] x : cells) {
                    data.setText(x[0], x[1], grid.get(0).get(0));
                    data.setRich(x[0], x[1], null);
                }
                return;
            }
            while (data.rowCount() < r0 + grid.size()) data.insertRow(data.rowCount());
            int cols = grid.stream().mapToInt(List::size).max().orElse(1);
            while (data.columnCount() < c0 + cols) {
                double w = colW.length > 0 ? colW[colW.length - 1] : 120;
                data.insertColumn(data.columnCount());
                data.columnWidths.set(data.columnCount() - 1, w);
                element.width += w;
            }
            for (int i = 0; i < grid.size(); i++) {
                for (int j = 0; j < grid.get(i).size(); j++) {
                    data.setText(r0 + i, c0 + j, grid.get(i).get(j));
                    data.setRich(r0 + i, c0 + j, null);
                }
            }
        });
        ar = r0;
        ac = c0;
        fr = r0 + grid.size() - 1;
        fc = c0 + grid.stream().mapToInt(List::size).max().orElse(1) - 1;
        this.grid.setFocus();
        this.grid.redraw();
    }

    /** Tab-separated text (quoted cells may hold tabs, line breaks and doubled quotes) as rows of cells. */
    static List<List<String>> parseTsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, start = true;
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            if (ch == '"' && start) {
                quoted = true;
                start = false;
                continue;
            }
            start = false;
            if (ch == '\t') {
                row.add(cell.toString());
                cell.setLength(0);
                start = true;
            } else if (ch == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                start = true;
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString());
        rows.add(row);
        return rows;
    }

    /** Text whose lines have the same number of tab-separated cells (pasted on the page, it makes a table). */
    static boolean looksLikeTable(String text) {
        if (!text.contains("\t")) return false;
        List<List<String>> rows = parseTsv(text);
        if (rows.size() < 1) return false;
        int n = rows.get(0).size();
        return n > 1 && rows.stream().allMatch(r -> r.size() == n);
    }

    static TableData tableFromTsv(String text) {
        List<List<String>> rows = parseTsv(text);
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        TableData t = new TableData(rows.size(), cols, 480);
        t.headerRow = false;
        for (int r = 0; r < rows.size(); r++) for (int c = 0; c < rows.get(r).size(); c++) t.setText(r, c, rows.get(r).get(c));
        // Columns fitted to the contents (an estimate: 7 points a character).
        double total = 0;
        for (int c = 0; c < cols; c++) {
            int longest = 4;
            for (List<String> row : rows) if (c < row.size()) longest = Math.max(longest, row.get(c).length());
            double w = Math.min(320, Math.max(60, longest * 7.5 + 16));
            t.columnWidths.set(c, w);
            total += w;
        }
        return t;
    }

    // MARK: Menus and Quick Help

    private String cellDetail(int r, int c) {
        String raw = data.text(r, c);
        if (Formula.isFormula(raw)) return "Formula " + raw + " = " + values.display(r, c) + ". ";
        return "";
    }

    private QuickHelpItem describe(Point p) {
        int border = borderAt(p.x);
        if (border >= 0) return new QuickHelpItem("Column Border", "Drag to resize column " + Formula.columnName(border)
                + " (the table grows or shrinks with it); double-click to fit it to its widest cell.");
        int[] cell = cellAt(p.x, p.y);
        if (cell == null) return null;
        return new QuickHelpItem("Cell " + Formula.columnName(cell[1]) + (cell[0] + 1), cellDetail(cell[0], cell[1])
                + "Click to type in it; drag, Shift-click or Shift-arrows to select cells, Ctrl-click to add one. Right-click for rows, columns, shading, sorting and more.");
    }

    private void fillCellMenu(Menu menu) {
        for (MenuItem mi : menu.getItems()) mi.dispose();
        int rows = Math.max(1, targetRows().length), cols = Math.max(1, targetColumns().length);
        boolean typing = editor() != null && fr < 0;
        if (typing) {
            item(menu, "Cut", editor::cut);
            item(menu, "Copy", editor::copy);
            item(menu, "Paste", editor::paste);
        } else {
            item(menu, "Cut", this::cutCells);
            item(menu, "Copy", this::copyCells);
            item(menu, "Paste", this::pasteCells);
            item(menu, "Clear Cells", this::clearCells);
        }
        new MenuItem(menu, SWT.SEPARATOR);
        item(menu, rows > 1 ? "Insert " + rows + " Rows Above" : "Insert Row Above", () -> insertRows(false));
        item(menu, rows > 1 ? "Insert " + rows + " Rows Below" : "Insert Row Below", () -> insertRows(true));
        item(menu, cols > 1 ? "Insert " + cols + " Columns Left" : "Insert Column Left", () -> insertColumns(false));
        item(menu, cols > 1 ? "Insert " + cols + " Columns Right" : "Insert Column Right", () -> insertColumns(true));
        item(menu, rows > 1 ? "Delete Rows" : "Delete Row", this::deleteRows);
        item(menu, cols > 1 ? "Delete Columns" : "Delete Column", this::deleteColumns);
        item(menu, "Delete Table", this::deleteTable);
        new MenuItem(menu, SWT.SEPARATOR);
        item(menu, "Move Row Up", () -> moveRows(true));
        item(menu, "Move Row Down", () -> moveRows(false));
        new MenuItem(menu, SWT.SEPARATOR);
        colorMenu(menu, "Shade Selected Cells", this::shadeCells);
        colorMenu(menu, "Header Row Shading", this::shadeHeader);
        colorMenu(menu, "Row Shading", this::shadeRows);
        colorMenu(menu, "Column Shading", this::shadeColumns);
        colorMenu(menu, "Banded Rows", this::shadeBanded);
        colorMenu(menu, "Table Shading", this::shadeTable);
        MenuItem textColor = new MenuItem(menu, SWT.CASCADE);
        textColor.setText("Text Color");
        Menu tm = new Menu(textColor);
        textColor.setMenu(tm);
        MenuItem auto = item(tm, "Automatic", () -> changeCells("Text Color", s -> s.withColor(null)));
        auto.setImage(Gfx.colorChip(null));
        for (Object[] c : Palettes.TEXT) {
            MenuItem mi = item(tm, (String) c[0], () -> changeCells("Text Color", s -> s.withColor((RGB) c[1])));
            mi.setImage(Gfx.colorChip((RGB) c[1]));
        }
        MenuItem align = new MenuItem(menu, SWT.CASCADE);
        align.setText("Alignment");
        Menu am = new Menu(align);
        align.setMenu(am);
        item(am, "Left", () -> alignCells(ParaStyle.LEFT));
        item(am, "Center", () -> alignCells(ParaStyle.CENTER));
        item(am, "Right", () -> alignCells(ParaStyle.RIGHT));
        new MenuItem(menu, SWT.SEPARATOR);
        item(menu, "Sort A to Z", () -> sort(true));
        item(menu, "Sort Z to A", () -> sort(false));
        if (!data.headerRow) {
            MenuItem inc = new MenuItem(menu, SWT.CHECK);
            inc.setText("Sort Includes First Row");
            inc.setSelection(page.store.prefs().getBoolean("tableSortIncludesFirstRow", false));
            inc.addListener(SWT.Selection, e -> page.store.prefs().putBoolean("tableSortIncludesFirstRow", inc.getSelection()));
        }
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem formulas = new MenuItem(menu, SWT.CASCADE);
        formulas.setText("Formulas");
        Menu fm = new Menu(formulas);
        formulas.setMenu(fm);
        item(fm, "AutoSum", this::autoSum);
        new MenuItem(fm, SWT.SEPARATOR);
        for (String[] f : Formula.MENU_FUNCTIONS) item(fm, f[0] + " — " + f[1], () -> insertFunction(f[0]));
        item(menu, "Merge Cells", this::mergeCells).setEnabled(fr >= 0 && (ar != fr || ac != fc));
        item(menu, "Unmerge Cells", this::unmergeCells).setEnabled(data.span(fr >= 0 ? fr : Math.max(0, er), fr >= 0 ? fc : Math.max(0, ec)) != null);
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem header = new MenuItem(menu, SWT.CHECK);
        header.setText("Header Row");
        header.setSelection(data.headerRow);
        header.addListener(SWT.Selection, e -> toggleHeaderRow());
        MenuItem borders = new MenuItem(menu, SWT.CHECK);
        borders.setText("Hide Borders");
        borders.setSelection(Boolean.TRUE.equals(data.hideBorders));
        borders.addListener(SWT.Selection, e -> toggleBorders());
        item(menu, "Auto-Fit Column Width", () -> {
            int[] cs = targetColumns();
            change("Auto-Fit Column", () -> {
                for (int c : cs) autoFit(c);
            });
        });
        item(menu, "Auto-Fit All Columns", () -> change("Auto-Fit All Columns", () -> {
            for (int c = 0; c < data.columnCount(); c++) if (!data.isColumnHidden(c)) autoFit(c);
        }));
        item(menu, "Select Table", this::selectAll);
    }

    private void colorMenu(Menu menu, String title, java.util.function.Consumer<RGB> apply) {
        MenuItem mi = new MenuItem(menu, SWT.CASCADE);
        mi.setText(title);
        Menu sub = new Menu(mi);
        mi.setMenu(sub);
        MenuItem none = item(sub, "No Color", () -> apply.accept(null));
        none.setImage(Gfx.colorChip(null));
        for (Object[] c : Palettes.SHADING) {
            MenuItem x = item(sub, (String) c[0], () -> apply.accept((RGB) c[1]));
            x.setImage(Gfx.colorChip((RGB) c[1]));
        }
    }

    @Override
    void fillMenu(Menu menu) {
        item(menu, "Select Table", this::selectAll);
        new MenuItem(menu, SWT.SEPARATOR);
        super.fillMenu(menu);
    }

    @Override
    void doubleClickGrip() {
        beginEditing(0, 0);
    }
}
