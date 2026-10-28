package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatasql.Catalog.ColumnInfo;
import com.k3jdr.mxdatasql.GridResult.CellEdit;
import com.k3jdr.mxdatasql.GridResult.CellKey;
import com.k3jdr.mxdatasql.GridFilters.GridQueryOptions;

/**
 * The DB Table grid (DataGridView.swift), drawn on a canvas so it handles thousands of rows: a # column, column
 * headers with an Excel-style sort / filter button, cells selected like a spreadsheet (click, shift-click, drag,
 * arrow keys; # selects rows, a header selects a column), in-place editing, pending edits highlighted, copy and paste
 * as tab-separated text, Set to NULL and Revert. Columns span the width ({@code visibleColumns} of them) except those
 * the user resized, which keep their width.
 */
public final class DataGrid extends Canvas {
    /** What the grid asks of its form. */
    public interface Host {
        void applyOptions(GridQueryOptions options);
        void widthsChanged(Map<String, Double> widths);
        void viewJSON(CellKey key);
        void openFilter(int column, Rectangle buttonOnDisplay);
        void editsChanged();
    }

    /** The rectangle of selected cells between {@code anchor} (first click) and {@code cursor}. */
    public record Selection(CellKey anchor, CellKey cursor) {
        public int top() { return Math.min(anchor.row(), cursor.row()); }
        public int bottom() { return Math.max(anchor.row(), cursor.row()); }
        public int left() { return Math.min(anchor.column(), cursor.column()); }
        public int right() { return Math.max(anchor.column(), cursor.column()); }
        public int rowCount() { return bottom() - top() + 1; }
        public int columnCount() { return right() - left() + 1; }

        public boolean contains(int row, int column) {
            return row >= top() && row <= bottom() && column >= left() && column <= right();
        }

        public List<CellKey> keys() {
            List<CellKey> out = new ArrayList<>();
            for (int r = top(); r <= bottom(); r++) for (int c = left(); c <= right(); c++) out.add(new CellKey(r, c));
            return out;
        }
    }

    static final int ROW_NUMBER_WIDTH = 56;
    static final int COLUMN_WIDTH = 160;
    static final int MIN_FITTED_WIDTH = 60;
    static final int MIN_WIDTH = 40;
    static final int BUTTON_WIDTH = 18;

    private static final Color GRID_LINE = new Color(new RGB(222, 224, 229));
    private static final Color HEADER_BG = new Color(new RGB(240, 241, 244));
    private static final Color ALT_ROW = new Color(new RGB(246, 247, 250));
    private static final Color SELECTED = new Color(new RGB(196, 220, 247));
    private static final Color SELECTED_EDITED = new Color(new RGB(220, 226, 170));
    private static final Color NULL_TEXT = new Color(new RGB(160, 162, 170));

    private final Session session;
    private Host host;
    private GridResult grid;
    private int loadedVersion = Integer.MIN_VALUE;
    private String loadedWidthKey;
    private final Map<String, Integer> userWidths = new HashMap<>();
    private int[] widths = new int[0];
    private int visibleColumns = 3;
    private Selection selection;
    private CellKey contextKey;
    private int rowHeight = 22, headerHeight = 28;
    private int topRow, scrollX;
    private Text editor;
    private CellKey editing;
    private int resizing = -1, resizeStartX, resizeStartWidth;
    private boolean dragging;
    private final Font italic;
    private final Cursor resizeCursor;

    public DataGrid(Composite parent, Session session) {
        super(parent, SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER | SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.session = session;
        FontData[] fd = getFont().getFontData();
        for (FontData f : fd) f.setStyle(SWT.ITALIC);
        italic = new Font(getDisplay(), fd);
        resizeCursor = new Cursor(getDisplay(), SWT.CURSOR_SIZEWE);
        addListener(SWT.Dispose, e -> {
            italic.dispose();
            resizeCursor.dispose();
        });
        GC gc = new GC(this);
        rowHeight = gc.getFontMetrics().getHeight() + 7;
        headerHeight = rowHeight + 6;
        gc.dispose();

        addListener(SWT.Paint, this::paint);
        addListener(SWT.Resize, e -> {
            fitColumns();
            updateScrollBars();
        });
        getVerticalBar().addListener(SWT.Selection, e -> {
            commitEditor();
            topRow = getVerticalBar().getSelection();
            redraw();
        });
        getHorizontalBar().addListener(SWT.Selection, e -> {
            commitEditor();
            scrollX = getHorizontalBar().getSelection();
            redraw();
        });
        addListener(SWT.MouseDown, this::mouseDown);
        addListener(SWT.MouseMove, this::mouseMove);
        addListener(SWT.MouseUp, this::mouseUp);
        addListener(SWT.MouseDoubleClick, this::doubleClick);
        addListener(SWT.KeyDown, this::keyDown);
        addListener(SWT.Traverse, e -> {
            switch (e.detail) {
                case SWT.TRAVERSE_TAB_NEXT, SWT.TRAVERSE_TAB_PREVIOUS, SWT.TRAVERSE_RETURN -> e.doit = false;
                default -> { }
            }
        });
        addListener(SWT.FocusIn, e -> redraw());
        addListener(SWT.MenuDetect, this::menuDetect);
        setMenu(new Menu(this));
        QuickHelp.provider(this, (control, p) -> describe(p));
    }

    public void setHost(Host host) { this.host = host; }

    /** The height that shows exactly {@code rows} rows under the header, with the horizontal scroll bar. */
    public int heightFor(int rows) {
        return headerHeight + rows * rowHeight + getHorizontalBar().getSize().y + 4;
    }

    public Selection selection() { return selection; }

    public GridResult grid() { return grid; }

    // MARK: Data

    /** Reloads from the session when its grid was replaced or edits were discarded; else just redraws. */
    public void refresh(int visibleColumns, Map<String, Double> savedWidths) {
        boolean fit = this.visibleColumns != visibleColumns;
        this.visibleColumns = visibleColumns;
        if (session.gridVersion != loadedVersion) {
            loadedVersion = session.gridVersion;
            cancelEditor();
            GridResult fresh = session.grid;
            String key = fresh == null ? null : fresh.widthKey();
            if (key == null || !key.equals(loadedWidthKey)) {
                loadedWidthKey = key;
                userWidths.clear();
                if (savedWidths != null) savedWidths.forEach((n, w) -> userWidths.put(n, (int) Math.round(w)));
            }
            grid = fresh;
            selection = null;
            contextKey = null;
            topRow = 0;
            scrollX = 0;
            widths = new int[grid == null ? 0 : grid.columns.size()];
            fit = true;
        }
        if (fit) fitColumns();
        updateScrollBars();
        redraw();
    }

    /** Sizes the columns the user has not resized so the first {@code visibleColumns} span the width. */
    private void fitColumns() {
        if (grid == null || widths.length == 0) return;
        int available = getClientArea().width - ROW_NUMBER_WIDTH;
        if (available <= 0) available = COLUMN_WIDTH * Math.max(1, visibleColumns);
        int visible = Math.min(widths.length, Math.max(1, visibleColumns));
        int fixedSum = 0, fixedCount = 0;
        for (int i = 0; i < visible; i++) {
            Integer w = userWidths.get(grid.columns.get(i).name());
            if (w != null) {
                fixedSum += w;
                fixedCount++;
            }
        }
        int flexible = visible - fixedCount;
        int width = flexible > 0 ? (available - fixedSum) / flexible : available / visible;
        int fitted = Math.max(MIN_FITTED_WIDTH, width);
        for (int i = 0; i < widths.length; i++) {
            Integer w = userWidths.get(grid.columns.get(i).name());
            widths[i] = w != null ? w : fitted;
        }
        updateScrollBars();
        redraw();
    }

    private int totalWidth() {
        int sum = 0;
        for (int w : widths) sum += w;
        return sum;
    }

    private int visibleRows() {
        return Math.max(1, (getClientArea().height - headerHeight) / rowHeight);
    }

    private int rowCount() { return grid == null ? 0 : grid.rows.size(); }

    private void updateScrollBars() {
        ScrollBar v = getVerticalBar();
        int rows = rowCount(), page = visibleRows();
        v.setValues(Math.min(topRow, Math.max(0, rows - page)), 0, Math.max(rows, 1), Math.min(page, Math.max(rows, 1)), 1, page);
        topRow = v.getSelection();
        ScrollBar h = getHorizontalBar();
        int view = Math.max(1, getClientArea().width - ROW_NUMBER_WIDTH);
        int total = Math.max(totalWidth(), 1);
        h.setValues(Math.min(scrollX, Math.max(0, total - view)), 0, total, Math.min(view, total), 20, view);
        scrollX = h.getSelection();
        v.setVisible(true);
        h.setVisible(true);
    }

    /** The left edge of a column in client coordinates. */
    private int columnX(int column) {
        int x = ROW_NUMBER_WIDTH - scrollX;
        for (int i = 0; i < column; i++) x += widths[i];
        return x;
    }

    private Rectangle cellRect(int row, int column) {
        return new Rectangle(columnX(column), headerHeight + (row - topRow) * rowHeight, widths[column], rowHeight);
    }

    /** The data column at x (client coordinates); -1 for the # column, -2 past the last column. */
    private int columnAt(int x) {
        if (x < ROW_NUMBER_WIDTH) return -1;
        int left = ROW_NUMBER_WIDTH - scrollX;
        for (int i = 0; i < widths.length; i++) {
            if (x >= left && x < left + widths[i]) return i;
            left += widths[i];
        }
        return -2;
    }

    /** The row at y, or -1 (header, or past the last row). */
    private int rowAt(int y) {
        if (y < headerHeight) return -1;
        int r = topRow + (y - headerHeight) / rowHeight;
        return r < rowCount() ? r : -1;
    }

    /** The column whose right edge is within 4 pixels of x in the header, or -1. */
    private int borderAt(int x, int y) {
        if (y >= headerHeight || grid == null) return -1;
        int right = ROW_NUMBER_WIDTH - scrollX;
        for (int i = 0; i < widths.length; i++) {
            right += widths[i];
            if (right > ROW_NUMBER_WIDTH && Math.abs(x - right) <= 4) return i;
        }
        return -1;
    }

    private Rectangle buttonRect(int column) {
        int x = columnX(column) + widths[column] - BUTTON_WIDTH - 2;
        return new Rectangle(x, 0, BUTTON_WIDTH, headerHeight);
    }

    // MARK: Drawing

    private void paint(Event e) {
        GC gc = e.gc;
        Rectangle area = getClientArea();
        gc.setBackground(Ui.WHITE);
        gc.fillRectangle(area);
        if (grid == null) return;
        gc.setAntialias(SWT.ON);
        int rows = rowCount();
        int last = Math.min(rows, topRow + visibleRows() + 1);
        boolean focused = isFocusControl() || (editor != null && !editor.isDisposed());
        // cells
        gc.setClipping(ROW_NUMBER_WIDTH, headerHeight, Math.max(0, area.width - ROW_NUMBER_WIDTH), area.height - headerHeight);
        for (int r = topRow; r < last; r++) {
            int y = headerHeight + (r - topRow) * rowHeight;
            int x = ROW_NUMBER_WIDTH - scrollX;
            for (int c = 0; c < widths.length; c++) {
                int w = widths[c];
                if (x + w >= ROW_NUMBER_WIDTH && x < area.width) drawCell(gc, r, c, x, y, w, focused);
                x += w;
            }
        }
        // header
        gc.setClipping(ROW_NUMBER_WIDTH, 0, Math.max(0, area.width - ROW_NUMBER_WIDTH), headerHeight);
        int x = ROW_NUMBER_WIDTH - scrollX;
        for (int c = 0; c < widths.length; c++) {
            drawHeader(gc, c, x, widths[c]);
            x += widths[c];
        }
        gc.setClipping((Rectangle) null);
        // row numbers
        gc.setBackground(HEADER_BG);
        gc.fillRectangle(0, 0, ROW_NUMBER_WIDTH, area.height);
        gc.setForeground(Ui.SECONDARY);
        Point hash = gc.textExtent("#");
        gc.drawText("#", ROW_NUMBER_WIDTH - 8 - hash.x, (headerHeight - hash.y) / 2, true);
        for (int r = topRow; r < last; r++) {
            int y = headerHeight + (r - topRow) * rowHeight;
            String num = Help.n(grid.offset + r + 1L);
            Point ext = gc.textExtent(num);
            boolean rowSelected = selection != null && r >= selection.top() && r <= selection.bottom();
            if (rowSelected) {
                gc.setBackground(SELECTED);
                gc.fillRectangle(0, y, ROW_NUMBER_WIDTH, rowHeight);
                gc.setBackground(HEADER_BG);
            }
            gc.drawText(num, ROW_NUMBER_WIDTH - 8 - ext.x, y + (rowHeight - ext.y) / 2, true);
            gc.setForeground(GRID_LINE);
            gc.drawLine(0, y + rowHeight - 1, ROW_NUMBER_WIDTH, y + rowHeight - 1);
            gc.setForeground(Ui.SECONDARY);
        }
        gc.setForeground(Ui.BORDER);
        gc.drawLine(ROW_NUMBER_WIDTH - 1, 0, ROW_NUMBER_WIDTH - 1, area.height);
        gc.drawLine(0, headerHeight - 1, area.width, headerHeight - 1);
    }

    private void drawCell(GC gc, int r, int c, int x, int y, int w, boolean focused) {
        CellKey key = new CellKey(r, c);
        CellEdit edit = session.edits.get(key);
        boolean selected = selection != null && selection.contains(r, c);
        Color bg = selected ? (edit != null ? SELECTED_EDITED : SELECTED) : edit != null ? Ui.EDITED : (r % 2 == 1 ? ALT_ROW : Ui.WHITE);
        gc.setBackground(bg);
        gc.fillRectangle(x, y, w, rowHeight);
        String value = edit != null ? edit.value() : grid.rows.get(r)[c];
        if (value == null) {
            gc.setFont(italic);
            gc.setForeground(NULL_TEXT);
            drawClipped(gc, "NULL", x + 5, y, w - 10);
            gc.setFont(getFont());
        } else {
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            drawClipped(gc, oneLine(value), x + 5, y, w - 10);
        }
        gc.setForeground(GRID_LINE);
        gc.drawLine(x + w - 1, y, x + w - 1, y + rowHeight - 1);
        gc.drawLine(x, y + rowHeight - 1, x + w - 1, y + rowHeight - 1);
        if (selected && focused && selection.cursor().equals(key) && !selection.anchor().equals(selection.cursor())) {
            gc.setForeground(Ui.ACCENT);
            gc.drawRectangle(x, y, w - 2, rowHeight - 2);
        } else if (selected && focused && selection.anchor().equals(selection.cursor())) {
            gc.setForeground(Ui.ACCENT);
            gc.drawRectangle(x, y, w - 2, rowHeight - 2);
        }
    }

    private static String oneLine(String v) {
        String s = v.length() > 400 ? v.substring(0, 400) : v;
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\t') >= 0
                ? s.replace("\r\n", " ⏎ ").replace('\n', '⏎').replace('\r', '⏎').replace('\t', ' ') : s;
    }

    private void drawClipped(GC gc, String text, int x, int y, int width) {
        if (width <= 4) return;
        String shown = shorten(gc, text, width);
        Point ext = gc.textExtent(shown);
        gc.drawText(shown, x, y + (rowHeight - ext.y) / 2, true);
    }

    static String shorten(GC gc, String text, int width) {
        if (gc.textExtent(text).x <= width) return text;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (gc.textExtent(text.substring(0, mid) + "…").x <= width) lo = mid; else hi = mid - 1;
        }
        return text.substring(0, lo) + "…";
    }

    private void drawHeader(GC gc, int c, int x, int w) {
        ColumnInfo col = grid.columns.get(c);
        boolean columnSelected = selection != null && selection.top() == 0 && selection.bottom() == rowCount() - 1
                && c >= selection.left() && c <= selection.right();
        gc.setBackground(columnSelected ? SELECTED : HEADER_BG);
        gc.fillRectangle(x, 0, w, headerHeight);
        gc.setForeground(Ui.BORDER);
        gc.drawLine(x + w - 1, 3, x + w - 1, headerHeight - 4);
        boolean button = grid.canFilter(c);
        GridQueryOptions o = grid.options;
        Boolean ascending = o.sort != null && o.sort.column().equals(col.name()) ? o.sort.ascending() : null;
        int titleWidth = w - 10 - (button ? BUTTON_WIDTH + 2 : 0) - (ascending != null ? 12 : 0);
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        String title = shorten(gc, col.name(), Math.max(0, titleWidth));
        Point ext = gc.textExtent(title);
        if (titleWidth > 4) gc.drawText(title, x + 5, (headerHeight - ext.y) / 2, true);
        if (!button) return;
        Rectangle b = buttonRect(c);
        if (ascending != null) {
            gc.setForeground(Ui.ACCENT);
            String arrow = ascending ? "↑" : "↓";
            Point a = gc.textExtent(arrow);
            gc.drawText(arrow, b.x - 12 + (12 - a.x) / 2, (headerHeight - a.y) / 2, true);
        }
        int cx = b.x + b.width / 2, cy = headerHeight / 2;
        if (o.filters.containsKey(col.name())) {
            // a funnel on an accent disc
            gc.setBackground(Ui.ACCENT);
            gc.fillOval(cx - 7, cy - 7, 14, 14);
            gc.setForeground(Ui.WHITE);
            gc.setLineWidth(2);
            gc.drawLine(cx - 4, cy - 3, cx + 4, cy - 3);
            gc.drawLine(cx - 2, cy, cx + 2, cy);
            gc.drawLine(cx - 1, cy + 3, cx + 1, cy + 3);
            gc.setLineWidth(1);
        } else {
            gc.setForeground(Ui.SECONDARY);
            gc.drawRoundRectangle(cx - 6, cy - 6, 12, 12, 4, 4);
            gc.setBackground(Ui.SECONDARY);
            gc.fillPolygon(new int[] { cx - 3, cy - 1, cx + 4, cy - 1, cx, cy + 3 });
        }
    }

    // MARK: Mouse

    private void mouseDown(Event e) {
        if (grid == null) return;
        if (e.button != 1) {
            if (e.button == 3) prepareContextMenu(e.x, e.y);
            return;
        }
        commitEditor();
        forceFocus();
        boolean extend = (e.stateMask & SWT.SHIFT) != 0;
        int border = borderAt(e.x, e.y);
        if (border >= 0) {
            resizing = border;
            resizeStartX = e.x;
            resizeStartWidth = widths[border];
            return;
        }
        int column = columnAt(e.x);
        if (e.y < headerHeight) {
            if (column >= 0) {
                if (grid.canFilter(column) && buttonRect(column).contains(e.x, e.y) && host != null) {
                    Rectangle b = buttonRect(column);
                    Point p = toDisplay(columnX(column), headerHeight);
                    host.openFilter(column, new Rectangle(p.x, p.y, widths[column], b.height));
                    return;
                }
                if (rowCount() > 0) {
                    int anchor = extend && selection != null ? selection.anchor().column() : column;
                    setSelection(new Selection(new CellKey(0, anchor), new CellKey(rowCount() - 1, column)), false);
                }
            }
            return;
        }
        int row = rowAt(e.y);
        if (row < 0 || column == -2) {
            setSelection(null, false);
            return;
        }
        if (column == -1) {
            if (widths.length == 0) return;
            int anchorRow = extend && selection != null ? selection.anchor().row() : row;
            setSelection(new Selection(new CellKey(anchorRow, 0), new CellKey(row, widths.length - 1)), false);
            return;
        }
        select(new CellKey(row, column), extend);
        dragging = true;
    }

    private void mouseMove(Event e) {
        if (grid == null) return;
        if (resizing >= 0) {
            widths[resizing] = Math.max(MIN_WIDTH, resizeStartWidth + e.x - resizeStartX);
            updateScrollBars();
            redraw();
            return;
        }
        if (dragging && (e.stateMask & SWT.BUTTON1) != 0) {
            Rectangle area = getClientArea();
            if (e.y < headerHeight && topRow > 0) {
                getVerticalBar().setSelection(topRow - 1);
                topRow = getVerticalBar().getSelection();
            } else if (e.y > area.height && topRow + visibleRows() < rowCount()) {
                getVerticalBar().setSelection(topRow + 1);
                topRow = getVerticalBar().getSelection();
            }
            int row = rowAt(Math.max(headerHeight, Math.min(e.y, area.height - 1)));
            if (row < 0) row = Math.min(rowCount() - 1, topRow + visibleRows() - 1);
            int column = columnAt(Math.max(ROW_NUMBER_WIDTH, Math.min(e.x, area.width - 1)));
            if (column == -2) column = widths.length - 1;
            if (row >= 0 && column >= 0) select(new CellKey(row, column), true);
            return;
        }
        setCursor(borderAt(e.x, e.y) >= 0 ? resizeCursor : null);
    }

    private void mouseUp(Event e) {
        dragging = false;
        if (resizing >= 0) {
            userWidths.put(grid.columns.get(resizing).name(), widths[resizing]);
            resizing = -1;
            fitColumns();
            saveWidths();
        }
    }

    private void doubleClick(Event e) {
        if (grid == null || e.button != 1 || e.y < headerHeight) return;
        int row = rowAt(e.y), column = columnAt(e.x);
        if (row >= 0 && column >= 0) beginEditing(new CellKey(row, column));
    }

    private void saveWidths() {
        if (host == null) return;
        Map<String, Double> out = new TreeMap<>();
        userWidths.forEach((n, w) -> out.put(n, (double) w));
        host.widthsChanged(out);
    }

    public boolean hasUserWidths() { return !userWidths.isEmpty(); }

    public void resetColumnWidths() {
        userWidths.clear();
        fitColumns();
        saveWidths();
    }

    // MARK: Selection

    public void select(CellKey key, boolean extend) {
        if (grid == null || rowCount() == 0 || widths.length == 0) return;
        CellKey k = new CellKey(clamp(key.row(), rowCount() - 1), clamp(key.column(), widths.length - 1));
        if (extend && selection != null) setSelection(new Selection(selection.anchor(), k), true);
        else setSelection(new Selection(k, k), true);
    }

    private static int clamp(int v, int max) { return Math.max(0, Math.min(v, max)); }

    private void setSelection(Selection s, boolean reveal) {
        selection = s;
        if (s != null && reveal) reveal(s.cursor());
        redraw();
        QuickHelp.get().update();
    }

    private void reveal(CellKey key) {
        int page = visibleRows();
        if (key.row() < topRow) topRow = key.row();
        else if (key.row() >= topRow + page) topRow = key.row() - page + 1;
        getVerticalBar().setSelection(topRow);
        topRow = getVerticalBar().getSelection();
        int left = columnX(key.column()) + scrollX - ROW_NUMBER_WIDTH;
        int view = getClientArea().width - ROW_NUMBER_WIDTH;
        if (left < scrollX) scrollX = left;
        else if (left + widths[key.column()] > scrollX + view) scrollX = Math.max(0, left + widths[key.column()] - view);
        getHorizontalBar().setSelection(scrollX);
        scrollX = getHorizontalBar().getSelection();
    }

    public void selectAll() {
        if (rowCount() == 0 || widths.length == 0) return;
        setSelection(new Selection(new CellKey(0, 0), new CellKey(rowCount() - 1, widths.length - 1)), false);
    }

    public boolean canSelectAll() { return rowCount() > 0; }

    // MARK: Keyboard

    private void keyDown(Event e) {
        if (grid == null || rowCount() == 0 || widths.length == 0) return;
        boolean extend = (e.stateMask & SWT.SHIFT) != 0;
        if ((e.stateMask & (SWT.CTRL | SWT.ALT)) != 0) return;
        CellKey cursor = selection != null ? selection.cursor() : new CellKey(0, 0);
        int page = visibleRows();
        switch (e.keyCode) {
            case SWT.ARROW_LEFT -> move(cursor, 0, -1, extend);
            case SWT.ARROW_RIGHT -> move(cursor, 0, 1, extend);
            case SWT.ARROW_DOWN -> move(cursor, 1, 0, extend);
            case SWT.ARROW_UP -> move(cursor, -1, 0, extend);
            case SWT.PAGE_DOWN -> move(cursor, page, 0, extend);
            case SWT.PAGE_UP -> move(cursor, -page, 0, extend);
            case SWT.HOME -> move(cursor, 0, -widths.length, extend);
            case SWT.END -> move(cursor, 0, widths.length, extend);
            case SWT.TAB -> move(cursor, 0, extend ? -1 : 1, false);
            case SWT.CR, SWT.KEYPAD_CR -> {
                if (selection != null) beginEditing(cursor);
            }
            case SWT.DEL, SWT.BS -> {
                if (grid.isEditable() && selection != null) {
                    for (CellKey k : selection.keys()) session.setCell(k.row(), k.column(), "");
                    editsChanged();
                }
            }
            default -> { }
        }
    }

    private void move(CellKey cursor, int dr, int dc, boolean extend) {
        if (selection == null) {
            select(new CellKey(0, 0), false);
            return;
        }
        select(new CellKey(cursor.row() + dr, cursor.column() + dc), extend);
    }

    // MARK: Editing

    /** Double-click or Enter: edit the cell in place (editable grids only). */
    public void beginEditing(CellKey key) {
        select(key, false);
        if (grid == null || !grid.isEditable()) return;
        commitEditor();
        reveal(key);
        redraw();
        Rectangle r = cellRect(key.row(), key.column());
        if (r.x < ROW_NUMBER_WIDTH) {
            r.width -= ROW_NUMBER_WIDTH - r.x;
            r.x = ROW_NUMBER_WIDTH;
        }
        editing = key;
        editor = new Text(this, SWT.SINGLE | SWT.BORDER);
        String value = session.displayed(key.row(), key.column());
        editor.setText(value == null ? "" : value);
        editor.setBounds(r.x, r.y - 1, Math.max(r.width, 80), r.height + 2);
        editor.selectAll();
        editor.setFocus();
        editor.addListener(SWT.Traverse, e -> {
            switch (e.detail) {
                case SWT.TRAVERSE_RETURN -> {
                    e.doit = false;
                    commitEditor();
                    forceFocus();
                }
                case SWT.TRAVERSE_ESCAPE -> {
                    e.doit = false;
                    cancelEditor();
                    forceFocus();
                }
                case SWT.TRAVERSE_TAB_NEXT, SWT.TRAVERSE_TAB_PREVIOUS -> {
                    e.doit = false;
                    CellKey at = editing;
                    commitEditor();
                    forceFocus();
                    if (at != null) select(new CellKey(at.row(), at.column() + (e.detail == SWT.TRAVERSE_TAB_NEXT ? 1 : -1)), false);
                }
                default -> { }
            }
        });
        editor.addListener(SWT.FocusOut, e -> getDisplay().asyncExec(this::commitEditor));
    }

    private void commitEditor() {
        if (editor == null || editor.isDisposed()) {
            editor = null;
            return;
        }
        CellKey key = editing;
        String text = editor.getText();
        Text old = editor;
        editor = null;
        editing = null;
        old.dispose();
        if (key == null || grid == null || key.row() >= rowCount()) return;
        String current = session.displayed(key.row(), key.column());
        if (current == null && text.isEmpty()) return;   // an empty field on a NULL cell means "left alone"
        if (text.equals(current)) return;
        session.setCell(key.row(), key.column(), text);
        editsChanged();
    }

    private void cancelEditor() {
        if (editor != null && !editor.isDisposed()) editor.dispose();
        editor = null;
        editing = null;
    }

    private void editsChanged() {
        redraw();
        if (host != null) host.editsChanged();
    }

    public void setNull() {
        if (grid == null || !grid.isEditable() || selection == null) return;
        for (CellKey k : selection.keys()) session.setCell(k.row(), k.column(), null);
        editsChanged();
    }

    public void revert() {
        if (selection == null) return;
        for (CellKey k : selection.keys()) session.edits.remove(k);
        editsChanged();
    }

    private boolean canRevert() {
        if (selection == null) return false;
        for (CellKey k : selection.keys()) if (session.edits.containsKey(k)) return true;
        return false;
    }

    /** Applies the text of View JSON… as the cell's pending edit. */
    public void applyCell(CellKey key, String value) {
        session.setCell(key.row(), key.column(), value);
        editsChanged();
    }

    // MARK: Copy and paste

    public boolean canCopy() { return selection != null && grid != null; }

    public boolean canPaste() {
        return grid != null && grid.isEditable() && selection != null && Clipboards.text(getDisplay()) != null;
    }

    /** One cell copies as its plain value; several as tab-separated rows. NULL copies as an empty value. */
    public void copy(boolean withNames) {
        if (!canCopy()) return;
        Selection s = selection;
        List<String> lines = new ArrayList<>();
        if (withNames) {
            List<String> names = new ArrayList<>();
            for (int c = s.left(); c <= s.right(); c++) names.add(grid.columns.get(c).name());
            lines.add(TextFormats.tsvLine(names));
        }
        for (int r = s.top(); r <= s.bottom(); r++) {
            List<String> fields = new ArrayList<>();
            for (int c = s.left(); c <= s.right(); c++) {
                String v = session.displayed(r, c);
                fields.add(v == null ? "" : v);
            }
            lines.add(TextFormats.tsvLine(fields));
        }
        boolean single = !withNames && s.rowCount() == 1 && s.columnCount() == 1;
        String text;
        if (single) {
            String v = session.displayed(s.top(), s.left());
            text = v == null ? "" : v;
        } else {
            text = String.join("\r\n", lines);
        }
        if (text.isEmpty()) text = "";
        if (!text.isEmpty()) Clipboards.copyText(getDisplay(), text);
    }

    /** Pastes tab-separated text as pending edits from the top-left selected cell; one value fills the selection. */
    public void paste() {
        if (!canPaste()) return;
        String text = Clipboards.text(getDisplay());
        List<List<String>> values = TextFormats.parseTSV(text);
        if (values.isEmpty()) return;
        Selection s = selection;
        Selection pasted;
        if (values.size() == 1 && values.get(0).size() == 1) {
            for (CellKey k : s.keys()) session.setCell(k.row(), k.column(), values.get(0).get(0));
            pasted = s;
        } else {
            CellKey top = new CellKey(s.top(), s.left());
            int bottomRow = top.row(), rightColumn = top.column();
            for (int r = 0; r < values.size() && top.row() + r < rowCount(); r++) {
                List<String> line = values.get(r);
                for (int c = 0; c < line.size() && top.column() + c < widths.length; c++) {
                    session.setCell(top.row() + r, top.column() + c, line.get(c));
                    bottomRow = Math.max(bottomRow, top.row() + r);
                    rightColumn = Math.max(rightColumn, top.column() + c);
                }
            }
            pasted = new Selection(top, new CellKey(bottomRow, rightColumn));
        }
        selection = pasted;
        editsChanged();
    }

    // MARK: Menus

    private void prepareContextMenu(int x, int y) {
        contextKey = null;
        int row = rowAt(y), column = columnAt(x);
        if (row >= 0 && column >= 0) {
            CellKey key = new CellKey(row, column);
            contextKey = key;
            if (selection == null || !selection.contains(row, column)) select(key, false);
        }
    }

    private void menuDetect(Event e) {
        Menu menu = getMenu();
        for (MenuItem mi : menu.getItems()) mi.dispose();
        if (grid == null) {
            e.doit = false;
            return;
        }
        Point p = toControl(e.x, e.y);
        if (e.detail == SWT.MENU_KEYBOARD && selection != null) {
            contextKey = selection.cursor();
        }
        if (p.y < headerHeight && e.detail != SWT.MENU_KEYBOARD) {
            item(menu, "Clear Sort", grid.options.sort != null, () -> {
                GridQueryOptions o = grid.options.copy();
                o.sort = null;
                host.applyOptions(o);
            });
            item(menu, "Clear All Filters", !grid.options.filters.isEmpty(), () -> {
                GridQueryOptions o = grid.options.copy();
                o.filters.clear();
                host.applyOptions(o);
            });
            new MenuItem(menu, SWT.SEPARATOR);
            item(menu, "Reset Column Widths", hasUserWidths(), this::resetColumnWidths);
            return;
        }
        if (e.detail == SWT.MENU_MOUSE) prepareContextMenu(p.x, p.y);
        CellKey json = jsonCell();
        item(menu, "View JSON…", json != null, () -> host.viewJSON(json));
        new MenuItem(menu, SWT.SEPARATOR);
        item(menu, "Copy\tCtrl+C", canCopy(), () -> copy(false));
        item(menu, "Copy with Column Names", canCopy(), () -> copy(true));
        item(menu, "Paste\tCtrl+V", canPaste(), this::paste);
        new MenuItem(menu, SWT.SEPARATOR);
        item(menu, "Set to NULL", grid.isEditable() && selection != null, this::setNull);
        item(menu, "Revert", canRevert(), this::revert);
    }

    private static void item(Menu menu, String text, boolean enabled, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(text);
        mi.setEnabled(enabled);
        mi.addListener(SWT.Selection, e -> action.run());
    }

    /** The right-clicked cell (or the selection's cursor) when it holds JSON. */
    public CellKey jsonCell() {
        CellKey key = contextKey != null ? contextKey : selection != null ? selection.cursor() : null;
        if (grid == null || key == null || key.row() >= rowCount() || key.column() >= widths.length) return null;
        String value = session.displayed(key.row(), key.column());
        return JsonFormatter.looksLikeJSON(value, grid.columns.get(key.column()).type()) ? key : null;
    }

    // MARK: Quick Help

    private QuickHelpItem describe(Point p) {
        if (grid == null) return null;
        int column = columnAt(p.x);
        if (p.y < headerHeight) {
            if (column == -1) return Help.ROW_NUMBERS;
            if (column < 0) return null;
            if (grid.canFilter(column) && buttonRect(column).contains(p)) return Help.filterButton(grid.columns.get(column));
            return Help.header(session, column);
        }
        int row = rowAt(p.y);
        if (row < 0) return null;
        if (column == -1) return Help.rowNumber(session, row);
        if (column < 0) return null;
        return Help.cell(session, new CellKey(row, column));
    }
}
