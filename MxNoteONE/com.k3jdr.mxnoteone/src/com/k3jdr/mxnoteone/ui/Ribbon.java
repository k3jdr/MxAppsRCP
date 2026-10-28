package com.k3jdr.mxnoteone.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxnoteone.model.Kinds.PageColor;
import com.k3jdr.mxnoteone.model.Kinds.RuleLines;
import com.k3jdr.mxnoteone.model.Kinds.TagKind;
import com.k3jdr.mxnoteone.store.Templates;
import com.k3jdr.mxnoteone.table.Formula;
import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.Fonts;
import com.k3jdr.mxnoteone.text.ParaStyle;
import com.k3jdr.mxnoteone.text.Paragraphs;

/**
 * The ribbon (RibbonView.swift): Home (font and size showing the selection's, bold / italic / underline /
 * strikethrough lit when the selection has them, super/subscript, highlight and font colour, clear formatting,
 * lists, indent, alignment, styles, tags), Insert, View, and a Table tab while a table is in use. Every tool has
 * a short tooltip; Quick Help says more.
 */
final class Ribbon {
    final CTabFolder folder;
    private final Commands cmd;
    private final PageView page;
    private Combo font, size;
    private ToolItem bold, italic, underline, strike, sup, sub;
    private CTabItem tableTab;
    private RGB lastHighlight = (RGB) Palettes.HIGHLIGHT[0][1], lastColor = (RGB) Palettes.TEXT[2][1];
    Runnable fullPage = () -> {}, tagSummary = () -> {}, recycleBin = () -> {};
    private ToolItem snap;

    static final double[] SIZES = { 8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 72 };

    Ribbon(Composite parent, Commands cmd, PageView page) {
        this.cmd = cmd;
        this.page = page;
        folder = new CTabFolder(parent, SWT.FLAT | SWT.TOP);
        folder.setTabHeight(24);
        folder.setSimple(true);
        home();
        insert();
        view();
        folder.setSelection(0);
        QuickHelp.provider(folder, (c, p) -> {
            CTabItem it = folder.getItem(p);
            if (it == null) return null;
            return new QuickHelpItem(it.getText() + " tab", switch (it.getText()) {
                case "Home" -> "Fonts, sizes, bold and the other text formatting, lists, indents, alignment, styles and tags.";
                case "Insert" -> "Tables, pictures, file attachments, links, dates and times, and page templates.";
                case "View" -> "The page's colour and rule lines, zoom, Full Page View, Tag Summary and the Recycle Bin.";
                default -> "Rows, columns, merging, shading, header row, borders, sorting and formulas for the table being worked on.";
            });
        });
    }

    // MARK: Building

    private Composite tab(String title) {
        CTabItem item = new CTabItem(folder, SWT.NONE);
        item.setText(title);
        Composite c = new Composite(folder, SWT.NONE);
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.wrap = true;
        rl.marginTop = rl.marginBottom = 2;
        rl.spacing = 10;
        rl.center = true;
        c.setLayout(rl);
        item.setControl(c);
        return c;
    }

    /** A section of tools with its name under them. */
    private Composite group(Composite tab, String name, String detail) {
        Composite g = new Composite(tab, SWT.NONE);
        g.setLayout(Ui.grid(1, 0, 0));
        Composite tools = new Composite(g, SWT.NONE);
        tools.setLayout(Ui.grid(10, 0, 2));
        Label l = new Label(g, SWT.CENTER);
        l.setText(name);
        l.setFont(Gfx.uiFont(-1, SWT.NORMAL));
        l.setForeground(Ui.SECONDARY);
        l.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, true, false));
        QuickHelp.help(l, name + " section", detail);
        return tools;
    }

    private ToolBar bar(Composite group) {
        ToolBar b = new ToolBar(group, SWT.FLAT | SWT.RIGHT);
        QuickHelp.provider(b, (c, p) -> {
            ToolItem it = b.getItem(p);
            return it != null && it.getData() instanceof QuickHelpItem q ? q : null;
        });
        return b;
    }

    private ToolItem tool(ToolBar bar, int style, String text, Image image, String tip, String detail, String shortcut, Runnable action) {
        ToolItem it = new ToolItem(bar, style);
        if (image != null) it.setImage(image);
        else it.setText(text);
        it.setToolTipText(tip + (shortcut == null ? "" : " (" + shortcut + ")"));
        it.setData(new QuickHelpItem(tip, detail, shortcut));
        if (action != null) it.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.ARROW) return;
            action.run();
        });
        return it;
    }

    /** A tool with a menu under its arrow. */
    private ToolItem dropDown(ToolBar bar, String text, Image image, String tip, String detail, Runnable click, Consumer<Menu> fill) {
        ToolItem it = tool(bar, SWT.DROP_DOWN, text, image, tip, detail, null, null);
        Menu menu = new Menu(bar.getShell(), SWT.POP_UP);
        it.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.ARROW || click == null) {
                for (MenuItem mi : menu.getItems()) mi.dispose();
                fill.accept(menu);
                Rectangle r = it.getBounds();
                Point p = bar.toDisplay(r.x, r.y + r.height);
                menu.setLocation(p);
                menu.setVisible(true);
            } else {
                click.run();
            }
        });
        return it;
    }

    private static void colors(Menu menu, Object[][] palette, String noneLabel, Consumer<RGB> pick) {
        if (noneLabel != null) {
            MenuItem none = ElementView.item(menu, noneLabel, () -> pick.accept(null));
            none.setImage(Gfx.colorChip(null));
        }
        for (Object[] c : palette) {
            MenuItem mi = ElementView.item(menu, (String) c[0], () -> pick.accept((RGB) c[1]));
            mi.setImage(Gfx.colorChip((RGB) c[1]));
        }
    }

    // MARK: Home

    private void home() {
        Composite tab = tab("Home");
        Composite fontGroup = group(tab, "Font", "The selected text's font, size and formatting. With table cells selected, every selected cell's text.");
        ((org.eclipse.swt.layout.GridLayout) fontGroup.getLayout()).numColumns = 3;
        font = new Combo(fontGroup, SWT.DROP_DOWN);
        GridData fg = new GridData();
        fg.widthHint = 150;
        font.setLayoutData(fg);
        Set<String> names = new LinkedHashSet<>(List.of(Fonts.SHORT_LIST));
        names.addAll(Gfx.installedFamilies());
        font.setItems(names.toArray(new String[0]));
        font.setVisibleItemCount(20);
        font.addListener(SWT.Selection, e -> applyFont());
        font.addListener(SWT.DefaultSelection, e -> applyFont());
        QuickHelp.help(font, "Font", "The selected text's font. Your most used fonts are at the top; then every font on this PC. Helvetica Neue and Menlo (the Mac's) are drawn with Arial and Consolas here.");
        size = new Combo(fontGroup, SWT.DROP_DOWN);
        GridData sg = new GridData();
        sg.widthHint = 44;
        size.setLayoutData(sg);
        for (double d : SIZES) size.add(fmt(d));
        size.addListener(SWT.Selection, e -> applySize());
        size.addListener(SWT.DefaultSelection, e -> applySize());
        QuickHelp.help(size, "Font Size", "The selected text's size in points (the Mac's points: text keeps its size on both systems).");
        ToolBar b = bar(fontGroup);
        GridData bg = new GridData();
        bg.horizontalSpan = 3;
        b.setLayoutData(bg);
        bold = tool(b, SWT.CHECK, "B", Gfx.glyph("B", SWT.BOLD, null), "Bold",
                "Makes the selected text bold, or plain again. With nothing selected, it applies to what you type next.", "Ctrl+B", cmd::toggleBold);
        italic = tool(b, SWT.CHECK, "I", Gfx.glyph("I", SWT.ITALIC, null), "Italic", "Makes the selected text italic, or upright again.", "Ctrl+I",
                cmd::toggleItalic);
        underline = tool(b, SWT.CHECK, "U", Gfx.glyph("U", SWT.UNDERLINE_SINGLE, null), "Underline", "Underlines the selected text, or takes the underline off.",
                "Ctrl+U", cmd::toggleUnderline);
        strike = tool(b, SWT.CHECK, "S", Gfx.glyph("abc", SWT.UNDERLINE_ERROR, null), "Strikethrough", "Draws a line through the selected text.", "Ctrl+-",
                cmd::toggleStrike);
        sup = tool(b, SWT.CHECK, "x²", Gfx.glyph("x²", 0, null), "Superscript", "Raises the selected text (x²).", "Ctrl+Shift+=",
                () -> cmd.toggleScript(1));
        sub = tool(b, SWT.CHECK, "x₂", Gfx.glyph("x₂", 0, null), "Subscript", "Lowers the selected text (H₂O).", "Ctrl+=", () -> cmd.toggleScript(-1));
        new ToolItem(b, SWT.SEPARATOR);
        tool(b, SWT.PUSH, "A+", Gfx.glyph("A⁺", 0, null), "Increase Font Size", "Makes the selected text a size larger.", "Ctrl+Shift+>", () -> cmd.stepSize(2));
        tool(b, SWT.PUSH, "A-", Gfx.glyph("A⁻", 0, null), "Decrease Font Size", "Makes the selected text a size smaller.", "Ctrl+Shift+<", () -> cmd.stepSize(-2));
        new ToolItem(b, SWT.SEPARATOR);
        dropDown(b, null, Gfx.glyph("ab", 0, lastHighlight), "Highlight", "Colours the background of the selected text, like a highlighter pen. The arrow picks the colour.",
                () -> cmd.setHighlight(lastHighlight), m -> colors(m, Palettes.HIGHLIGHT, "No Highlight", c -> {
                    if (c != null) lastHighlight = c;
                    cmd.setHighlight(c);
                }));
        dropDown(b, null, Gfx.glyph("A", SWT.BOLD, lastColor), "Font Color", "Colours the selected text. The arrow picks the colour.",
                () -> cmd.setColor(lastColor), m -> colors(m, Palettes.TEXT, "Automatic", c -> {
                    if (c != null) lastColor = c;
                    cmd.setColor(c);
                }));
        tool(b, SWT.PUSH, null, Gfx.glyph("A✕", 0, null), "Clear Formatting", "Removes fonts, colours and styles from the selected text.", "Ctrl+Shift+N",
                cmd::clearFormatting);

        Composite para = group(tab, "Paragraph", "Lists, indents and alignment of the paragraphs the selection touches.");
        ToolBar p = bar(para);
        tool(p, SWT.PUSH, "• List", null, "Bullets", "Turns the paragraphs into a bulleted list, or back to plain paragraphs.", "Ctrl+.", cmd::bullets);
        tool(p, SWT.PUSH, "1. List", null, "Numbering", "Turns the paragraphs into a numbered list.", "Ctrl+/", cmd::numbering);
        tool(p, SWT.PUSH, "⇤", null, "Outdent", "Moves the paragraphs out a level.", null, () -> cmd.indent(-1));
        tool(p, SWT.PUSH, "⇥", null, "Indent", "Moves the paragraphs in a level (Tab at the start of a list item does too).", null, () -> cmd.indent(1));
        new ToolItem(p, SWT.SEPARATOR);
        tool(p, SWT.PUSH, null, alignIcon(0), "Align Left", "Lines the paragraph up on the left.", null, () -> cmd.align(ParaStyle.LEFT));
        tool(p, SWT.PUSH, null, alignIcon(1), "Center", "Centres the paragraph.", null, () -> cmd.align(ParaStyle.CENTER));
        tool(p, SWT.PUSH, null, alignIcon(2), "Align Right", "Lines the paragraph up on the right.", null, () -> cmd.align(ParaStyle.RIGHT));
        tool(p, SWT.PUSH, null, alignIcon(3), "Justify", "Lines the paragraph up on both sides.", null, () -> cmd.align(ParaStyle.JUSTIFIED));

        Composite styles = group(tab, "Styles", "Headings, Title, Quote and other paragraph styles.");
        ToolBar s = bar(styles);
        dropDown(s, "Styles", null, "Styles", "Headings, Title, Quote and other paragraph styles (Ctrl+Alt+1 – 4, Ctrl+Alt+0 for Normal).", null, m -> {
            for (Paragraphs.Style st : Paragraphs.Style.values()) ElementView.item(m, st.title, () -> cmd.style(st));
        });
        tool(s, SWT.PUSH, "Code", null, "Code", "Formats the selection as code, in a monospaced font on a grey background.", "Ctrl+Alt+C",
                () -> cmd.style(Paragraphs.Style.CODE));

        Composite tags = group(tab, "Tags", "Tags mark paragraphs: To Do, Important, Question and more; the Tag Summary lists them all.");
        ToolBar t = bar(tags);
        tool(t, SWT.PUSH, TagKind.TODO.symbol + " To Do", null, "To Do", "Puts a check box in front of the paragraph; click it to check it off.", "Ctrl+1",
                () -> cmd.tag(TagKind.TODO));
        dropDown(t, "Tags", null, "Tags", "Marks the paragraph with a tag: To Do, Important, Question and more.", null, m -> {
            for (TagKind k : TagKind.values()) ElementView.item(m, k.symbol + "  " + k.title + "\tCtrl+" + k.digit(), () -> cmd.tag(k));
            new MenuItem(m, SWT.SEPARATOR);
            ElementView.item(m, "Remove Tag\tCtrl+0", cmd::removeTags);
        });
        tool(t, SWT.PUSH, "Tag Summary", null, "Tag Summary", "Opens a window listing every tagged paragraph in your notebooks.", "Ctrl+Shift+T", () -> tagSummary.run());
    }

    private static Image alignIcon(int kind) {
        String[] g = { "≡←", "≡", "→≡", "☰" };
        return Gfx.glyph(g[kind], 0, null);
    }

    private void applyFont() {
        String f = font.getText().strip();
        if (f.isEmpty() || f.equals("Mixed")) return;
        cmd.setFont(f.equals("System Font") ? ".AppleSystemUIFont" : f);
    }

    private void applySize() {
        try {
            double d = Double.parseDouble(size.getText().strip().replace(',', '.'));
            if (d > 0) cmd.setSize(d);
        } catch (NumberFormatException e) {
            // not a size: left as it was
        }
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    // MARK: Insert

    private void insert() {
        Composite tab = tab("Insert");
        Composite tables = group(tab, "Tables", "Tables, OneNote style: or type some text and press Tab.");
        ToolBar t = bar(tables);
        tool(t, SWT.PUSH, "Table…", null, "Table", "Inserts a table on the page.", "Ctrl+Alt+T", cmd::insertTable);
        Composite files = group(tab, "Files", "Pictures and file attachments, kept inside the notebook.");
        ToolBar f = bar(files);
        tool(f, SWT.PUSH, "Picture…", null, "Picture", "Inserts a picture from a file. You can also drop pictures onto the page.", "Ctrl+Alt+P",
                page::choosePictures);
        tool(f, SWT.PUSH, "File…", null, "File", "Attaches a file to the page; double-click it to open.", null, page::chooseAttachments);
        Composite links = group(tab, "Links", "Links to web pages, email addresses and pages in your notebooks.");
        ToolBar l = bar(links);
        tool(l, SWT.PUSH, "Link…", null, "Link", "Adds a link to the selected words: a web address, email or a page in your notebooks.", "Ctrl+K",
                cmd::insertLink);
        Composite time = group(tab, "Time Stamp", "Today's date and the time, typed where the caret is.");
        ToolBar ts = bar(time);
        tool(ts, SWT.PUSH, "Date", null, "Date", "Types today's date.", "Alt+Shift+D", cmd::insertDate);
        tool(ts, SWT.PUSH, "Time", null, "Time", "Types the time now.", "Alt+Shift+T", cmd::insertTime);
        tool(ts, SWT.PUSH, "Date & Time", null, "Date & Time", "Types today's date and the time.", "Alt+Shift+F", cmd::insertDateTime);
        Composite pages = group(tab, "Pages", "New pages laid out from a template.");
        ToolBar pg = bar(pages);
        dropDown(pg, "Page Templates", null, "Page Templates", "A new page laid out from a template.", null, m -> {
            for (Templates tpl : Templates.choices()) ElementView.item(m, tpl.title, () -> cmd.newPageFromTemplate(tpl));
        });
    }

    // MARK: View

    private void view() {
        Composite tab = tab("View");
        Composite pageGroup = group(tab, "Page", "The page's background colour and its rule lines or grid.");
        ToolBar p = bar(pageGroup);
        dropDown(p, "Page Color", null, "Page Color", "The page's background colour.", null, m -> {
            for (PageColor c : PageColor.values()) {
                MenuItem mi = ElementView.item(m, c.title(), () -> page.setPageColor(c));
                mi.setImage(Gfx.colorChip(c == PageColor.NONE ? null : c.rgb));
            }
        });
        dropDown(p, "Rule Lines", null, "Rule Lines", "Lines or a grid on the page, to write and line things up on.", null, m -> {
            for (RuleLines r : RuleLines.values()) ElementView.item(m, r.title, () -> page.setRuleLines(r));
        });
        snap = tool(p, SWT.CHECK, "Snap to Grid", null, "Snap to Grid",
                "Containers line up on the page's grid (its rule lines, or 12 points) as you place, drag, resize or nudge them.", "Ctrl+Alt+G",
                () -> page.snapToGrid = snap.getSelection());
        Composite zoom = group(tab, "Zoom", "How big the page is drawn.");
        ToolBar z = bar(zoom);
        tool(z, SWT.PUSH, "Zoom In", null, "Zoom In", "Makes the page bigger.", "Ctrl+Alt+=", page::zoomIn);
        tool(z, SWT.PUSH, "Zoom Out", null, "Zoom Out", "Makes the page smaller.", "Ctrl+Alt+-", page::zoomOut);
        tool(z, SWT.PUSH, "100%", null, "Actual Size", "Shows the page at 100%.", "Ctrl+Shift+0", () -> page.zoomTo(1));
        tool(z, SWT.PUSH, "Page Width", null, "Page Width", "Zooms so the page fills the window's width.", null, page::zoomToPageWidth);
        Composite window = group(tab, "Window", "The window's layout, and the notebooks' summaries.");
        ToolBar w = bar(window);
        tool(w, SWT.PUSH, "Full Page View", null, "Full Page View", "Hides the notebook and page lists so the page fills the window.", "F11", () -> fullPage.run());
        tool(w, SWT.PUSH, "Tag Summary", null, "Tag Summary", "Opens a window listing every tagged paragraph in your notebooks.", "Ctrl+Shift+T", () -> tagSummary.run());
        tool(w, SWT.PUSH, "Recycle Bin", null, "Recycle Bin", "Deleted pages and sections, kept for 60 days. Restore them from here.", null, () -> recycleBin.run());
    }

    void setSnap(boolean on) {
        if (snap != null && !snap.isDisposed()) snap.setSelection(on);
    }

    // MARK: Table

    /** Shows the Table tab while a table is in use (and hides it after). */
    void tableActive(boolean active) {
        if (active && tableTab == null) {
            tableTab = new CTabItem(folder, SWT.NONE);
            tableTab.setText("Table");
            Composite c = new Composite(folder, SWT.NONE);
            RowLayout rl = new RowLayout(SWT.HORIZONTAL);
            rl.wrap = true;
            rl.spacing = 10;
            rl.center = true;
            c.setLayout(rl);
            tableTab.setControl(c);
            buildTable(c);
        } else if (!active && tableTab != null) {
            boolean shown = folder.getSelection() == tableTab;
            tableTab.getControl().dispose();
            tableTab.dispose();
            tableTab = null;
            if (shown) folder.setSelection(0);
        }
    }

    private void onTable(Consumer<TableBox> action) {
        TableBox t = page.activeTable();
        if (t == null) folder.getDisplay().beep();
        else action.accept(t);
    }

    private void buildTable(Composite tab) {
        Composite rows = group(tab, "Rows & Columns", "Insert and delete rows and columns (as many as are selected), and move rows.");
        ToolBar r = bar(rows);
        tool(r, SWT.PUSH, "Insert Above", null, "Insert Above", "Adds rows above the selected cells (as many as are selected).", null, () -> onTable(t -> t.insertRows(false)));
        tool(r, SWT.PUSH, "Insert Below", null, "Insert Below", "Adds rows below the selected cells.", "Ctrl+Enter", () -> onTable(t -> t.insertRows(true)));
        tool(r, SWT.PUSH, "Insert Left", null, "Insert Left", "Adds columns to the left.", null, () -> onTable(t -> t.insertColumns(false)));
        tool(r, SWT.PUSH, "Insert Right", null, "Insert Right", "Adds columns to the right.", null, () -> onTable(t -> t.insertColumns(true)));
        dropDown(r, "Delete", null, "Delete", "Deletes the selected rows, columns, or the table.", null, m -> {
            ElementView.item(m, "Delete Rows", () -> onTable(TableBox::deleteRows));
            ElementView.item(m, "Delete Columns", () -> onTable(TableBox::deleteColumns));
            ElementView.item(m, "Delete Table", () -> onTable(TableBox::deleteTable));
        });
        tool(r, SWT.PUSH, "Row Up", null, "Row Up", "Moves the selected rows up.", "Alt+Shift+Up", () -> onTable(t -> t.moveRows(true)));
        tool(r, SWT.PUSH, "Row Down", null, "Row Down", "Moves the selected rows down.", "Alt+Shift+Down", () -> onTable(t -> t.moveRows(false)));
        Composite merge = group(tab, "Merge", "Join neighbouring cells into one, or split them again.");
        ToolBar mg = bar(merge);
        tool(mg, SWT.PUSH, "Merge Cells", null, "Merge Cells", "Joins the selected cells into one.", null, () -> onTable(TableBox::mergeCells));
        tool(mg, SWT.PUSH, "Split", null, "Split", "Splits merged cells back into their cells.", null, () -> onTable(TableBox::unmergeCells));
        Composite shading = group(tab, "Shading", "Colours for cells, rows, columns, the header row, banded rows or the whole table.");
        ToolBar sh = bar(shading);
        dropDown(sh, "Cells", null, "Cells", "Shades the selected cells with a colour.", null, m -> colors(m, Palettes.SHADING, "No Color", c -> onTable(t -> t.shadeCells(c))));
        dropDown(sh, "Header", null, "Header", "Shades the header row with a colour.", null, m -> colors(m, Palettes.SHADING, "No Color", c -> onTable(t -> t.shadeHeader(c))));
        dropDown(sh, "Banded Rows", null, "Banded Rows", "Shades every other row.", null, m -> colors(m, Palettes.SHADING, "No Color", c -> onTable(t -> t.shadeBanded(c))));
        dropDown(sh, "Table", null, "Table Shading", "Shades the whole table.", null, m -> colors(m, Palettes.SHADING, "No Color", c -> onTable(t -> t.shadeTable(c))));
        Composite look = group(tab, "Table", "The header row and the table's lines.");
        ToolBar lk = bar(look);
        tool(lk, SWT.PUSH, "Header Row", null, "Header Row", "Makes the first row the table's heading (bold and shaded), or an ordinary row again.", null,
                () -> onTable(TableBox::toggleHeaderRow));
        tool(lk, SWT.PUSH, "Hide Borders", null, "Hide Borders", "Hides the table's lines, or shows them again.", null, () -> onTable(TableBox::toggleBorders));
        tool(lk, SWT.PUSH, "Select Table", null, "Select Table", "Selects every cell.", null, () -> onTable(TableBox::selectAll));
        Composite data = group(tab, "Data", "Sorting and formulas.");
        ToolBar d = bar(data);
        tool(d, SWT.PUSH, "Sort A→Z", null, "Sort A→Z", "Sorts the rows by the selected column, smallest first (the header row stays first).", null,
                () -> onTable(t -> t.sort(true)));
        tool(d, SWT.PUSH, "Sort Z→A", null, "Sort Z→A", "Sorts the rows by the selected column, largest first.", null, () -> onTable(t -> t.sort(false)));
        tool(d, SWT.PUSH, "Σ AutoSum", null, "AutoSum", "Adds up the numbers above (or left of) the cell.", null, () -> onTable(TableBox::autoSum));
        dropDown(d, "Formulas", null, "Formulas", "SUM, AVERAGE and other Excel-style formulas: inserts the function with the caret inside its brackets.", null, m -> {
            for (String[] f : Formula.MENU_FUNCTIONS) ElementView.item(m, f[0] + " — " + f[1], () -> onTable(t -> t.insertFunction(f[0])));
        });
        tab.layout(true, true);
    }

    // MARK: Showing the selection's formatting

    /** Shows the formatting of the selection (or of what's typed next) in the Home tab. */
    void refresh() {
        if (folder.isDisposed()) return;
        List<CharStyle> styles = cmd.currentStyles();
        boolean any = !styles.isEmpty();
        Set<String> families = new LinkedHashSet<>();
        Set<Double> sizes = new LinkedHashSet<>();
        for (CharStyle s : styles) {
            families.add(Fonts.displayName(s.font()));
            sizes.add(Math.round(s.size() * 2) / 2.0);
        }
        String f = !any ? "" : families.size() == 1 ? families.iterator().next() : "Mixed";
        if (!font.isFocusControl() && !font.getText().equals(f)) font.setText(f);
        String z = !any ? "" : sizes.size() == 1 ? fmt(sizes.iterator().next()) : "–";
        if (!size.isFocusControl() && !size.getText().equals(z)) size.setText(z);
        bold.setSelection(any && styles.stream().allMatch(CharStyle::bold));
        italic.setSelection(any && styles.stream().allMatch(CharStyle::italic));
        underline.setSelection(any && styles.stream().allMatch(CharStyle::underline));
        strike.setSelection(any && styles.stream().allMatch(CharStyle::strike));
        sup.setSelection(any && styles.stream().allMatch(s -> s.script() > 0));
        sub.setSelection(any && styles.stream().allMatch(s -> s.script() < 0));
        tableActive(page.activeTable() != null);
    }
}
