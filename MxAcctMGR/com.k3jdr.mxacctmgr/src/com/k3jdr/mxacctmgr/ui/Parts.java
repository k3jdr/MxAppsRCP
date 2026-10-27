package com.k3jdr.mxacctmgr.ui;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.Screen;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.text.Lessons;

/**
 * The pieces every screen uses (Components.swift): the "Why this step" lesson card, metric tiles, boxed sections, the
 * step footer, the warning line and the "service not running" panel, tables, and the number and date formats.
 */
public final class Parts {
    private Parts() {}

    public static final Color LESSON_BG = new Color(new RGB(255, 250, 225));
    public static final Color LESSON_BORDER = new Color(new RGB(236, 214, 120));
    public static final Color TILE_BG = new Color(new RGB(241, 242, 245));
    public static final Color BOX_BG = new Color(new RGB(250, 250, 252));
    public static final Color OPEN_ROW = new Color(new RGB(255, 252, 228));
    public static final Color WARN_BG = new Color(new RGB(255, 241, 222));
    public static final Color MINE_BG = new Color(new RGB(234, 242, 252));

    // Text

    /** Read-only, selectable text that wraps at the width it is given (every screen's text can be copied). */
    public static Text text(Composite parent, String text) {
        Text t = new Text(parent, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        t.setText(text == null ? "" : text);
        t.setBackground(parent.getBackground());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.widthHint = 120;
        t.setLayoutData(gd);
        return t;
    }

    public static Text text(Composite parent, String text, org.eclipse.swt.graphics.Font font) {
        Text t = text(parent, text);
        t.setFont(font);
        return t;
    }

    /** A one-line selectable value (a figure, a code). */
    public static Text value(Composite parent, String text) {
        Text t = new Text(parent, SWT.READ_ONLY | SWT.SINGLE);
        t.setText(text == null ? "" : text);
        t.setBackground(parent.getBackground());
        return t;
    }

    public static Label secondary(Composite parent, String text) {
        Label l = Ui.label(parent, text);
        l.setForeground(Ui.SECONDARY);
        return l;
    }

    /** A wrapping secondary caption. */
    public static Label caption(Composite parent, String text) {
        Label l = Ui.wrap(parent, text);
        l.setForeground(Ui.SECONDARY);
        l.setFont(Ui.small());
        return l;
    }

    // Layout

    /** A row of controls, left to right. */
    public static Composite row(Composite parent, int columns) {
        Composite c = new Composite(parent, SWT.NONE);
        GridLayout l = Ui.grid(columns, 0, 8);
        c.setLayout(l);
        c.setLayoutData(Ui.fillH());
        return c;
    }

    /** A column of controls, top to bottom. */
    public static Composite column(Composite parent, int spacing) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(Ui.grid(1, 0, spacing));
        c.setLayoutData(Ui.fillH());
        return c;
    }

    /** An empty control that takes the row's spare width. */
    public static Label spacer(Composite row) {
        Label l = new Label(row, SWT.NONE);
        l.setLayoutData(Ui.fillH());
        return l;
    }

    // The lesson card

    /**
     * "Why this step": the idea behind a screen, before its numbers. Folds away under its title, remembered per screen;
     * the points are one text, so a drag selects across them.
     */
    public static Composite lessonCard(Composite parent, App app, String id, String title, List<String> points) {
        Composite card = new Composite(parent, SWT.BORDER);
        card.setLayout(Ui.grid(2, 10, 6));
        card.setLayoutData(Ui.fillH());
        card.setBackground(LESSON_BG);
        Label head = Ui.label(card, "💡  " + title, Settings.heading());
        head.setLayoutData(Ui.fillH());
        Button toggle = new Button(card, SWT.PUSH | SWT.FLAT);
        Text body = new Text(card, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        StringBuilder sb = new StringBuilder();
        for (String p : points) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("•  ").append(p);
        }
        body.setText(sb.toString());
        GridData bd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        bd.widthHint = 200;
        body.setLayoutData(bd);
        Ui.background(card, LESSON_BG);
        body.setBackground(LESSON_BG);
        Runnable apply = () -> {
            boolean c = app.collapsed(id);
            toggle.setText(c ? "Show ▾" : "Hide ▴");
            toggle.setToolTipText(c ? Help.LESSON_SHOW_HELP : Help.LESSON_HIDE_HELP);
            Ui.setVisible(body, !c);
        };
        apply.run();
        toggle.addListener(SWT.Selection, e -> {
            app.setCollapsed(id, !app.collapsed(id));
            apply.run();
            Page.relayout(card);
        });
        head.addListener(SWT.MouseUp, e -> {
            app.setCollapsed(id, !app.collapsed(id));
            apply.run();
            Page.relayout(card);
        });
        String all = String.join(" ", points);
        QuickHelp.help(card, title, all);
        QuickHelp.help(toggle, title, app.collapsed(id) ? Help.LESSON_SHOW_HELP : Help.LESSON_HIDE_HELP);
        PageText.section(card, title);
        return card;
    }

    /** The lesson card of a lesson from {@link Lessons}. */
    public static Composite lessonCard(Composite parent, App app, String id, Lessons.Lesson lesson) {
        return lessonCard(parent, app, id, lesson.title(), lesson.points());
    }

    // Tiles and boxes

    /** A row of {@code n} metric tiles of equal width. */
    public static Composite tiles(Composite parent, int n) {
        Composite c = new Composite(parent, SWT.NONE);
        GridLayout l = new GridLayout(n, true);
        l.marginWidth = l.marginHeight = 0;
        l.horizontalSpacing = 10;
        c.setLayout(l);
        c.setLayoutData(Ui.fillH());
        return c;
    }

    /** A metric tile: its title, the figure, a caption; Quick Help says what it measures. */
    public static Composite tile(Composite parent, String title, String value, String caption, Color tint, String help) {
        Composite t = new Composite(parent, SWT.NONE);
        t.setLayout(Ui.grid(1, 10, 2));
        t.setLayoutData(Ui.fillH());
        t.setBackground(TILE_BG);
        Label tl = Ui.label(t, title);
        tl.setForeground(Ui.SECONDARY);
        tl.setFont(Ui.small());
        Text v = value(t, value);
        v.setFont(Settings.figure());
        if (tint != null) v.setForeground(tint);
        v.setLayoutData(Ui.fillH());
        if (caption != null && !caption.isEmpty()) {
            Label c = Ui.wrap(t, caption);
            c.setForeground(Ui.SECONDARY);
            c.setFont(Ui.small());
        }
        Ui.background(t, TILE_BG);
        v.setBackground(TILE_BG);
        t.setToolTipText(help);
        QuickHelp.help(t, title, help);
        QuickHelp.help(v, title, help);
        return t;
    }

    /** A titled section; returns its content (a one-column grid). Quick Help says what the section holds. */
    public static Composite box(Composite parent, String title, String help) {
        Composite b = new Composite(parent, SWT.BORDER);
        b.setLayout(Ui.grid(1, 10, 8));
        b.setLayoutData(Ui.fillH());
        b.setBackground(BOX_BG);
        Label t = Ui.label(b, title, Settings.heading());
        t.setBackground(BOX_BG);
        Composite content = new Composite(b, SWT.NONE);
        content.setLayout(Ui.grid(1, 0, 6));
        content.setLayoutData(Ui.fillH());
        content.setBackground(BOX_BG);
        if (help != null) QuickHelp.help(b, title, help);
        PageText.section(b, title);
        return content;
    }

    /** An orange warning line (a problem the page can't hide). */
    public static Composite warning(Composite parent, String text) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(Ui.grid(2, 8, 8));
        c.setLayoutData(Ui.fillH());
        c.setBackground(WARN_BG);
        Label icon = Ui.label(c, "⚠");
        icon.setForeground(Ui.ORANGE);
        icon.setFont(Ui.bold());
        Text t = text(c, text);
        t.setBackground(WARN_BG);
        Ui.background(c, WARN_BG);
        t.setBackground(WARN_BG);
        return c;
    }

    /**
     * The accounts service (or another Layer 3 service) isn't running: what to do — the start command to copy, Try
     * Again, and Layer 3 Services.
     */
    public static Composite serviceDown(Composite parent, App app, String message, Runnable retry) {
        Composite c = new Composite(parent, SWT.BORDER);
        c.setLayout(Ui.grid(1, 12, 8));
        c.setLayoutData(Ui.fillH());
        c.setBackground(WARN_BG);
        Label head = Ui.label(c, "⚠  The accounts service is not running", Settings.heading());
        head.setForeground(Ui.ORANGE);
        Text msg = text(c, message);
        Ui.label(c, "Start the Layer 3 services (the accounts service listens on 127.0.0.1:8774) with:");
        Text cmd = value(c, ServiceCatalog.startCommand());
        cmd.setFont(Ui.mono());
        cmd.setLayoutData(Ui.fillH());
        Composite buttons = row(c, 4);
        QuickHelp.help(Ui.button(buttons, "Copy Command", () -> Clipboards.copyText(c.getDisplay(), ServiceCatalog.startCommand())),
                "Copy Command", "Copies the command that starts the Layer 3 services, to paste into a terminal.");
        if (retry != null) QuickHelp.help(Ui.button(buttons, "Try Again", retry), "Try Again", "Asks the service again.");
        QuickHelp.help(Ui.button(buttons, "Layer 3 Services…", () -> app.go(Screen.SERVICES)), "Layer 3 Services",
                "What each service is for, whether it runs, and how to start it.");
        Ui.background(c, WARN_BG);
        msg.setBackground(WARN_BG);
        cmd.setBackground(WARN_BG);
        QuickHelp.help(c, "Accounts service", "Banking and My Portfolio live in the security master, through the accounts Layer 3 service (Services/accounts, port 8774). Nothing is made up in its place: start it and Try Again.");
        return c;
    }

    /** True when a failure means the service isn't there (so the page shows {@link #serviceDown}). */
    public static boolean isDown(Throwable t) {
        return t instanceof com.k3jdr.mxacctmgr.port.ServiceException se && se.kind == com.k3jdr.mxacctmgr.port.ServiceException.Kind.NOT_RUNNING;
    }

    // Previous / Next

    /** Previous and Next along the guide, with where they lead (StepFooter). */
    public static void footer(Composite parent, App app) {
        Screen s = app.screen();
        Composite c = row(parent, 3);
        ((GridData) c.getLayoutData()).verticalIndent = 8;
        Screen prev = s.previous(), next = s.next();
        if (prev != null) {
            String t = app.title(prev, true);
            Button b = Ui.button(c, "‹  " + t, () -> app.go(prev));
            b.setToolTipText(String.format(Help.PREVIOUS_STEP_HELP_FORMAT, t));
            QuickHelp.help(b, Help.PREVIOUS_STEP_TITLE, String.format(Help.PREVIOUS_STEP_FORMAT, t), Help.PREVIOUS_STEP_SHORTCUT);
        } else {
            new Label(c, SWT.NONE);
        }
        spacer(c);
        if (next != null) {
            String t = app.title(next, true);
            Button b = Ui.button(c, String.format(Help.NEXT_STEP_LABEL_FORMAT, t) + "  ›", () -> app.go(next));
            b.setToolTipText(String.format(Help.NEXT_STEP_HELP_FORMAT, t));
            QuickHelp.help(b, Help.NEXT_STEP_TITLE, String.format(Help.NEXT_STEP_FORMAT, t), Help.NEXT_STEP_SHORTCUT);
        } else {
            new Label(c, SWT.NONE);
        }
    }

    // Tables

    /** A column: title, width, and SWT.LEFT / SWT.RIGHT / SWT.CENTER. */
    public record Col(String title, int width, int align) {
        public static Col l(String title, int width) { return new Col(title, width, SWT.LEFT); }
        public static Col r(String title, int width) { return new Col(title, width, SWT.RIGHT); }
        public static Col c(String title, int width) { return new Col(title, width, SWT.CENTER); }
    }

    /** A table with a header, its columns, Copy Row / Copy All Rows, and the table font. */
    public static Table table(Composite parent, int style, Col... cols) {
        Table t = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | style);
        t.setHeaderVisible(true);
        t.setLinesVisible(true);
        t.setFont(Settings.table());
        for (Col c : cols) {
            TableColumn tc = new TableColumn(t, c.align());
            tc.setText(c.title());
            tc.setWidth(c.width());
            tc.setMoveable(true);
        }
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.widthHint = 200;
        t.setLayoutData(gd);
        TableCopy.addMenuItems(t);
        return t;
    }

    /** Shows {@code rows} rows of the table at once (the rest scroll inside it). */
    public static void rows(Table t, int rows) {
        GridData gd = (GridData) t.getLayoutData();
        gd.heightHint = t.getHeaderHeight() + t.getItemHeight() * Math.max(1, rows) + 4;
    }

    /** "Rows: n" — how many rows of the tables show at once, one choice for every table (RowsStepper). */
    public static Spinner rowsControl(Composite parent, String what, Runnable changed) {
        Label l = Ui.label(parent, "Rows:");
        Spinner s = new Spinner(parent, SWT.BORDER);
        s.setValues(Settings.tableRows(), 6, 40, 0, 1, 5);
        String help = String.format(Help.ROWS_HELP_FORMAT, what);
        s.setToolTipText(help);
        QuickHelp.help(s, "Rows", help);
        QuickHelp.help(l, "Rows", help);
        s.addListener(SWT.Selection, e -> Settings.setTableRows(s.getSelection()));
        Runnable follow = () -> {
            if (s.isDisposed()) return;
            if (s.getSelection() != Settings.tableRows()) s.setSelection(Settings.tableRows());
            changed.run();
        };
        Settings.addRowsListener(follow);
        s.addListener(SWT.Dispose, e -> Settings.removeRowsListener(follow));
        return s;
    }

    /** Adds a context-menu item to a control's menu (creating it if needed). */
    public static void enable(Control c, boolean on) {
        if (!c.isDisposed()) c.setEnabled(on);
    }

    // Formats

    private static final NumberFormat USD = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter US_DAY = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /** Dollars and cents: $1,234.56, −$12.00 shown as -$12.00. */
    public static String money2(double x) {
        synchronized (USD) {
            return USD.format(x);
        }
    }

    public static String money2(Double x) { return x == null ? "—" : money2(x.doubleValue()); }

    /** A yyyy-MM-dd day as 10/27/2026. */
    public static String shortDay(String s) {
        if (s == null || s.length() < 10) return s == null ? "" : s;
        try {
            return LocalDate.parse(s.substring(0, 10), ISO).format(US_DAY);
        } catch (RuntimeException e) {
            return s;
        }
    }

    public static String today() { return LocalDate.now().format(ISO); }

    /** Parses an amount typed as 1,234.56 or $12 (null when it isn't one). */
    public static Double parseAmount(String s) {
        if (s == null) return null;
        String t = s.replace("$", "").replace(",", "").trim();
        if (t.isEmpty()) return null;
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses a date typed as yyyy-mm-dd or m/d/yyyy into yyyy-MM-dd (null when it isn't one). */
    public static String parseDay(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return LocalDate.parse(t, ISO).format(ISO);
        } catch (RuntimeException e) {
            // try the U.S. form
        }
        try {
            return LocalDate.parse(t, DateTimeFormatter.ofPattern("M/d/yyyy")).format(ISO);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
