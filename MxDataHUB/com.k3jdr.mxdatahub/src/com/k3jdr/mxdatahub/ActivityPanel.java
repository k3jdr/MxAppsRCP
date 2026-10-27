package com.k3jdr.mxdatahub;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.RefreshService.Entry;
import com.k3jdr.mxdatahub.RefreshService.Running;

/**
 * Data Refresh ▸ Activity (DataRefreshView.swift's ActivityPanel): the SRM loader service's activity log as a
 * console — a status strip for what runs now (or how the last refresh ended, or Idle), then every line the loaders
 * print, with filters by run, level and text. Follows new lines unless Follow is off. It is read-only text: drag
 * across lines (or Ctrl+A) and Ctrl+C or Copy to copy them; Copy with nothing selected copies every line shown.
 */
final class ActivityPanel extends Composite {
    enum Level { ALL("All"), INFO("Info"), WARNINGS("Warnings"), ERRORS("Errors");
        final String title;
        Level(String t) { title = t; }
    }

    static final Color BLUE = new Color(new RGB(0, 110, 230));
    static final Color TERTIARY = new Color(new RGB(160, 162, 170));
    static final Color RUNNING_BG = new Color(new RGB(226, 238, 252));

    private final DataRefreshScreen owner;
    private final Button follow, copy, clear, loadHistory;
    private final Composite strip;
    private final StackLayout stripStack = new StackLayout();
    private final Composite runningBox, endedBox, idleBox;
    private final Label runBadge, runName, runTime, runLoader, runLatest, endIcon, endText, endClock;
    private final ProgressBar runBar;
    private final Button showItsLines;
    private final Button[] levels = new Button[Level.values().length];
    private final Combo runCombo;
    private final Text search;
    private final Composite consoleHost;
    private final StackLayout consoleStack = new StackLayout();
    private final Placeholder empty;
    private final StyledText console;
    private final Label footerCounts, footerErrors, footerWarnings, footerUpdated;

    private Level level = Level.ALL;
    private Integer runFilter;
    private List<Integer> runIds = new ArrayList<>();
    private int clearedThrough;
    // What the console shows: to append new lines rather than rebuild.
    private Integer shownFirst, shownLast;
    private int shownCount;
    private List<Entry> visible = List.of();

    ActivityPanel(Composite parent, DataRefreshScreen owner) {
        super(parent, SWT.NONE);
        this.owner = owner;
        setLayout(Ui.grid(1, 0, 0));

        Composite top = new Composite(this, SWT.NONE);
        top.setLayout(Ui.grid(6, 10, 6));
        top.setLayoutData(Ui.fillH());
        Ui.label(top, "Activity", Ui.subheading());
        new Label(top, SWT.NONE).setLayoutData(Ui.fillH());
        follow = new Button(top, SWT.TOGGLE);
        follow.setText("⤓ Follow");
        follow.setSelection(true);
        follow.setToolTipText("Follow: scroll to new lines as they arrive");
        follow.addListener(SWT.Selection, e -> scrollToEnd());
        QuickHelp.help(follow, "Follow", "Scrolls to each new line as it arrives. Turn it off to read back while a load runs.");
        copy = Ui.button(top, "Copy", this::copy);
        copy.setToolTipText("Copy the selected lines, or all the lines shown when none are selected");
        QuickHelp.help(copy, "Copy", "Copies the lines you selected in the console (drag across them, or Ctrl+A), or every line shown when none are; "
                + "with their times, levels and loaders, for a ticket or a chat. Ctrl+C copies a selection too.");
        clear = Ui.button(top, "Clear", () -> {
            clearedThrough = owner.lastSeq;
            refresh();
        });
        clear.setToolTipText("Clear the lines shown (the service keeps them)");
        QuickHelp.help(clear, "Clear", "Hides the lines so far; new ones still appear. The service keeps its log.");
        loadHistory = Ui.button(top, "Load History", () -> owner.main.show(Section.LOADS));
        QuickHelp.help(loadHistory, "Load History", "Goes to Load History: every loader run (srm.load_run) with its rows and rejects, identifier coverage and the sources. "
                + "Needs the database connection (DB Connection).");

        // The status strip: running, how the last refresh ended, or idle.
        strip = new Composite(this, SWT.NONE);
        strip.setLayoutData(Ui.fillH());
        strip.setLayout(stripStack);
        runningBox = new Composite(strip, SWT.NONE);
        runningBox.setLayout(Ui.grid(1, 10, 4));
        Composite runTop = new Composite(runningBox, SWT.NONE);
        runTop.setLayout(Ui.grid(3, 0, 8));
        runTop.setLayoutData(Ui.fillH());
        runBadge = Ui.label(runTop, " RUNNING ", Ui.font(-1, SWT.BOLD));
        runBadge.setForeground(Ui.WHITE);
        runBadge.setBackground(Ui.ACCENT);
        runName = Ui.label(runTop, "", Ui.bold());
        runName.setLayoutData(Ui.fillH());
        runTime = Ui.label(runTop, "");
        runTime.setForeground(Ui.SECONDARY);
        runBar = new ProgressBar(runningBox, SWT.HORIZONTAL | SWT.SMOOTH);
        runBar.setLayoutData(Ui.fillH());
        Composite runBottom = new Composite(runningBox, SWT.NONE);
        runBottom.setLayout(Ui.grid(2, 0, 8));
        runBottom.setLayoutData(Ui.fillH());
        runLoader = Ui.label(runBottom, "");
        runLoader.setFont(Ui.mono());
        runLatest = Ui.label(runBottom, "");
        runLatest.setFont(Ui.mono());
        runLatest.setForeground(Ui.SECONDARY);
        GridData lg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        lg.widthHint = 100;
        runLatest.setLayoutData(lg);
        Ui.background(runningBox, RUNNING_BG);
        runBadge.setBackground(Ui.ACCENT);
        QuickHelp.help(runningBox, "Running Now", "The refresh the SRM loader service is running — from here, another window or MxJobsMGR: its current loader, "
                + "a bar by loaders finished, the latest line it printed and how long it has run.");

        endedBox = new Composite(strip, SWT.NONE);
        endedBox.setLayout(Ui.grid(4, 10, 6));
        endIcon = Ui.label(endedBox, "", Ui.bold());
        endText = Ui.label(endedBox, "", Ui.bold());
        endText.setLayoutData(Ui.fillH());
        endClock = Ui.label(endedBox, "");
        endClock.setForeground(Ui.SECONDARY);
        showItsLines = Ui.button(endedBox, "Show Its Lines", () -> {
            Entry end = lastEnd();
            if (end != null && end.run() != null) setRunFilter(end.run());
        });
        QuickHelp.help(showItsLines, "Show Its Lines", "Shows only that refresh's lines (Run ▸ All runs shows everything again).");
        QuickHelp.help(endedBox, "Last Refresh", "Nothing is running. How the last refresh ended, when, and how long it took.");

        idleBox = new Composite(strip, SWT.NONE);
        idleBox.setLayout(Ui.grid(1, 10, 6));
        Ui.label(idleBox, "☾  Idle — no refresh is running").setForeground(Ui.SECONDARY);
        QuickHelp.help(idleBox, "Idle", "The SRM loader service runs nothing now, and no refresh has ended since it started.");
        stripStack.topControl = idleBox;

        // Filters: level, run, text.
        Composite filters = new Composite(this, SWT.NONE);
        filters.setLayout(Ui.grid(Level.values().length + 2, 10, 4));
        filters.setLayoutData(Ui.fillH());
        for (Level l : Level.values()) {
            Button b = new Button(filters, SWT.TOGGLE);
            b.setText(l.title);
            b.setSelection(l == level);
            b.addListener(SWT.Selection, e -> {
                level = l;
                for (int i = 0; i < levels.length; i++) levels[i].setSelection(Level.values()[i] == l);
                refresh();
            });
            QuickHelp.help(b, "Level", "All lines; Info hides the Verbose / Trace detail lines (DBG); only warnings and errors; or only errors.");
            levels[l.ordinal()] = b;
        }
        runCombo = new Combo(filters, SWT.READ_ONLY);
        GridData rg = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        rg.widthHint = 180;
        runCombo.setLayoutData(rg);
        runCombo.add("All runs");
        runCombo.select(0);
        runCombo.addListener(SWT.Selection, e -> {
            int i = runCombo.getSelectionIndex();
            runFilter = i <= 0 || i - 1 >= runIds.size() ? null : runIds.get(i - 1);
            refresh();
        });
        QuickHelp.help(runCombo, "Run", "Shows one refresh's lines — its loaders' output and their start and end — or every run's.");
        search = new Text(filters, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        search.setMessage("Filter lines");
        search.setLayoutData(Ui.fillH());
        search.addListener(SWT.Modify, e -> refresh());
        QuickHelp.help(search, "Filter", "Shows only lines containing this text (a ticker, a loader, a word such as rejected).", "Ctrl+F");

        new Label(this, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
        consoleHost = new Composite(this, SWT.NONE);
        consoleHost.setLayoutData(Ui.fill());
        consoleHost.setLayout(consoleStack);
        empty = new Placeholder(consoleHost);
        console = new StyledText(consoleHost, SWT.READ_ONLY | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
        console.setFont(Ui.mono());
        console.setMargins(4, 6, 4, 6);
        console.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MODIFIER_MASK) == SWT.MOD1 && (e.keyCode == 'f' || e.keyCode == 'F')) {
                search.setFocus();
                search.selectAll();
                e.doit = false;
            }
        });
        QuickHelp.help(console, "Activity", "What the loaders print while they run, line by line, whoever started them (here, another window or MxJobsMGR): "
                + "each refresh and loader starting and ending with its status, time and rows, and warnings and errors marked. "
                + "Drag across lines and Ctrl+C to copy them tab-separated; Ctrl+F finds. Load History has every load's counts.");
        consoleStack.topControl = empty;

        new Label(this, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
        Composite footer = new Composite(this, SWT.NONE);
        footer.setLayout(Ui.grid(5, 10, 4));
        footer.setLayoutData(Ui.fillH());
        footerCounts = Ui.label(footer, "");
        footerErrors = Ui.label(footer, "");
        footerErrors.setForeground(Ui.RED);
        footerWarnings = Ui.label(footer, "");
        footerWarnings.setForeground(Ui.ORANGE);
        new Label(footer, SWT.NONE).setLayoutData(Ui.fillH());
        footerUpdated = Ui.label(footer, "");
        for (Control c : footer.getChildren()) {
            c.setFont(Ui.small());
            if (c != footerErrors && c != footerWarnings) c.setForeground(Ui.SECONDARY);
        }
        refresh();
    }

    private void setRunFilter(Integer run) {
        runFilter = run;
        refresh();
    }

    private Entry lastEnd() {
        List<Entry> a = owner.activity;
        for (int i = a.size() - 1; i >= 0; i--) if ("run-end".equals(a.get(i).event())) return a.get(i);
        return null;
    }

    /** Shows the log as it is now: the strip, the runs, the lines that pass the filters and the counts. */
    void refresh() {
        if (isDisposed()) return;
        loadHistory.setEnabled(owner.hub.isConnected());
        updateStrip();
        updateRuns();
        String text = search.getText().trim().toLowerCase();
        List<Entry> lines = new ArrayList<>();
        for (Entry e : owner.activity) {
            if (e.seq() <= clearedThrough) continue;
            if (runFilter != null && !Objects.equals(e.run(), runFilter)) continue;
            boolean levelOk = level == Level.ALL || "error".equals(e.level())
                    || (level == Level.INFO && !"debug".equals(e.level()))
                    || (level == Level.WARNINGS && "warning".equals(e.level()));
            if (!levelOk) continue;
            if (!text.isEmpty() && !e.text().toLowerCase().contains(text) && (e.loader() == null || !e.loader().toLowerCase().contains(text))) continue;
            lines.add(e);
        }
        visible = lines;
        copy.setEnabled(!lines.isEmpty());
        clear.setEnabled(!lines.isEmpty());
        if (lines.isEmpty()) {
            empty.show(owner.activity.isEmpty() ? "No Activity Yet" : "No Matching Lines", owner.activity.isEmpty()
                    ? "Refresh a category: what its loaders print appears here as they run, with warnings and errors marked. Loads MxJobsMGR runs through the service appear here too."
                    : "No lines match the run, level or filter.");
            consoleStack.topControl = empty;
            console.setText("");
            shownFirst = shownLast = null;
            shownCount = 0;
        } else {
            consoleStack.topControl = console;
            showLines(lines);
        }
        consoleHost.layout(true, true);
        long errors = lines.stream().filter(e -> "error".equals(e.level())).count();
        long warnings = lines.stream().filter(e -> "warning".equals(e.level())).count();
        footerCounts.setText(Fmt.count(lines.size()) + " of " + Fmt.count(owner.activity.size()) + " lines");
        footerErrors.setText(errors > 0 ? "✖ " + Fmt.count(errors) : "");
        footerWarnings.setText(warnings > 0 ? "⚠ " + Fmt.count(warnings) : "");
        footerUpdated.setText(owner.lastPoll == null ? "" : "Updated " + owner.lastPoll.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        footerCounts.getParent().layout(true);
    }

    /** The same lines with new ones after them (the usual poll): append those. Anything else (a filter): rebuild. */
    private void showLines(List<Entry> lines) {
        boolean appends = shownCount > 0 && lines.size() >= shownCount && Objects.equals(lines.get(0).seq(), shownFirst)
                && Objects.equals(lines.get(shownCount - 1).seq(), shownLast);
        boolean changed = !(appends && lines.size() == shownCount);
        if (!changed) return;
        org.eclipse.swt.graphics.Point sel = console.getSelectionRange();
        List<Entry> add = appends ? lines.subList(shownCount, lines.size()) : lines;
        if (!appends) console.setText("");
        for (Entry e : add) append(e);
        shownFirst = lines.get(0).seq();
        shownLast = lines.get(lines.size() - 1).seq();
        shownCount = lines.size();
        if (appends && sel.y > 0) console.setSelectionRange(sel.x, sel.y);
        if (follow.getSelection() && console.getSelectionCount() == 0) scrollToEnd();
    }

    private void scrollToEnd() {
        if (!follow.getSelection() || console.isDisposed()) return;
        console.setTopIndex(Math.max(0, console.getLineCount() - 1));
    }

    private int[] stops;

    /** Time, mark, loader, then the text; wrapped text continues under the text column. */
    private int[] stops() {
        if (stops == null) {
            GC gc = new GC(console);
            gc.setFont(Ui.mono());
            int ch = (int) Math.ceil(gc.getFontMetrics().getAverageCharacterWidth());
            gc.dispose();
            stops = new int[] { ch * 10, ch * 16, ch * 38 };
        }
        return stops;
    }

    private void append(Entry e) {
        int line = console.getLineCount() - 1;
        boolean isRun = "run-start".equals(e.event()) || "run-end".equals(e.event());
        String loader = e.loader() == null ? "" : e.loader().length() > 20 ? e.loader().substring(0, 19) + "…" : e.loader();
        int start = console.getCharCount();
        StringBuilder sb = new StringBuilder();
        List<StyleRange> ranges = new ArrayList<>();
        Font bold = Ui.font(0, SWT.BOLD);
        add(sb, ranges, start, e.clock() + "\t", TERTIARY, null);
        add(sb, ranges, start, mark(e) + "\t", markColor(e), e.event() == null ? null : bold);
        add(sb, ranges, start, loader + "\t", Ui.SECONDARY, null);
        add(sb, ranges, start, e.text() + "\n", textColor(e), isRun || e.event() != null ? bold : null);
        console.append(sb.toString());
        for (StyleRange r : ranges) {
            if (r.font != null) {
                // The console's font in bold.
                r.font = null;
                r.fontStyle = SWT.BOLD;
            }
            console.setStyleRange(r);
        }
        int[] s = stops();
        console.setLineTabStops(line, 1, s);
        console.setLineWrapIndent(line, 1, s[2]);
        if ("run-end".equals(e.event())) {
            // A rule between one refresh and the next: a tab out past the edge, struck through. Copies as one tab.
            int at = console.getCharCount();
            console.append("\t\n");
            StyleRange rule = new StyleRange(at, 1, Ui.BORDER, null);
            rule.strikeout = true;
            rule.strikeoutColor = Ui.BORDER;
            console.setStyleRange(rule);
            console.setLineTabStops(line + 1, 1, new int[] { 4000 });
        }
    }

    private static void add(StringBuilder sb, List<StyleRange> ranges, int base, String s, Color color, Font font) {
        StyleRange r = new StyleRange(base + sb.length(), s.length(), color, null);
        r.font = font;
        ranges.add(r);
        sb.append(s);
    }

    static String mark(Entry e) {
        if (e.event() == null) return switch (e.level()) {
            case "error" -> "ERR";
            case "warning" -> "WARN";
            case "debug" -> "DBG";
            default -> "";
        };
        return switch (e.event()) {
            case "run-start" -> "START";
            case "run-end" -> "END";
            case "loader-start" -> "▶";
            case "loader-end" -> switch (e.status() == null ? "" : e.status()) {
                case "succeeded" -> "✓";
                case "failed" -> "✗";
                case "dry-run" -> "·";
                default -> "!";
            };
            default -> "";
        };
    }

    static String outcome(Entry e) {
        return e.status() != null ? e.status() : "error".equals(e.level()) ? "failed" : "warning".equals(e.level()) ? "partial" : "succeeded";
    }

    /** The outcome's colour on -end lines (green, red, orange, blue for a preview), else null. */
    private static Color outcomeColor(Entry e) {
        if (!"run-end".equals(e.event()) && !"loader-end".equals(e.event())) return null;
        return stateColor(outcome(e));
    }

    static Color stateColor(String state) {
        return switch (state == null ? "" : state) {
            case "fresh", "succeeded" -> Ui.GREEN;
            case "due", "partial" -> Ui.ORANGE;
            case "stale", "failed" -> Ui.RED;
            case "dry-run", "running" -> BLUE;
            default -> Ui.SECONDARY;
        };
    }

    private static Color markColor(Entry e) {
        Color outcome = outcomeColor(e);
        if (outcome != null) return outcome;
        return switch (e.level()) {
            case "error" -> Ui.RED;
            case "warning" -> Ui.ORANGE;
            case "debug" -> TERTIARY;
            default -> e.event() != null ? Ui.ACCENT : Ui.SECONDARY;
        };
    }

    private static Color textColor(Entry e) {
        if ("run-end".equals(e.event())) return outcomeColor(e);
        if ("run-start".equals(e.event())) return Ui.ACCENT;
        return "error".equals(e.level()) ? Ui.RED : "debug".equals(e.level()) ? Ui.SECONDARY : null;
    }

    /** Runs in the log, newest first, labelled with their start time, name and outcome. */
    private void updateRuns() {
        Map<Integer, String> ends = new HashMap<>();
        for (Entry e : owner.activity) {
            if ("run-end".equals(e.event()) && e.run() != null) {
                ends.put(e.run(), e.status() != null ? e.status() : "error".equals(e.level()) ? "failed" : "warning".equals(e.level()) ? "with warnings" : "done");
            }
        }
        List<Integer> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = owner.activity.size() - 1; i >= 0; i--) {
            Entry e = owner.activity.get(i);
            if ("run-start".equals(e.event()) && e.run() != null) {
                ids.add(e.run());
                labels.add(e.clock() + " " + (e.name() != null ? e.name() : e.text()) + " — " + ends.getOrDefault(e.run(), "running"));
            }
        }
        List<String> items = new ArrayList<>();
        items.add("All runs");
        items.addAll(labels);
        if (!items.equals(List.of(runCombo.getItems()))) {
            runCombo.setItems(items.toArray(String[]::new));
        }
        runIds = ids;
        int at = runFilter == null ? 0 : ids.indexOf(runFilter) + 1;
        if (runCombo.getSelectionIndex() != at) runCombo.select(Math.max(0, at));
    }

    /** Always says what the service is doing: running a refresh, else how the last refresh ended, else idle. */
    void updateStrip() {
        Running run = owner.current;
        Control top;
        if (run != null) {
            runBadge.setText(run.dryRun() ? " PREVIEWING " : " RUNNING ");
            runName.setText(Ui.escape(run.name()));
            long secs = Math.max(0, Math.round(System.currentTimeMillis() / 1000.0 - run.started()));
            runTime.setText(LocalTime.ofSecondOfDay(Math.min(secs, 86399)).format(DateTimeFormatter.ofPattern("H:mm:ss")));
            runBar.setMaximum(Math.max(run.loaders().size(), 1));
            runBar.setSelection(Math.max(run.index() - 1, 0));
            runLoader.setText(run.loader() == null ? "" : Ui.escape(run.loader() + " · loader " + run.index() + " of " + run.loaders().size()));
            String latest = "";
            for (int i = owner.activity.size() - 1; i >= 0; i--) {
                Entry e = owner.activity.get(i);
                if (Objects.equals(e.run(), run.run()) && e.event() == null) {
                    latest = e.text();
                    break;
                }
            }
            runLatest.setText(Ui.escape(latest));
            top = runningBox;
        } else {
            Entry end = lastEnd();
            if (end != null) {
                String status = outcome(end);
                Color c = stateColor(status);
                endIcon.setText(switch (status) {
                    case "succeeded" -> "✔";
                    case "failed" -> "✖";
                    case "dry-run" -> "◉";
                    default -> "⚠";
                });
                endIcon.setForeground(c);
                endText.setText(Ui.escape("Last refresh: " + (end.name() == null ? "" : end.name()) + " — " + status
                        + (end.seconds() == null ? "" : " in " + Fmt.plain(end.seconds()) + " s")));
                endClock.setText("ended " + end.clock());
                Ui.setVisible(showItsLines, end.run() != null && !Objects.equals(runFilter, end.run()));
                top = endedBox;
            } else {
                top = idleBox;
            }
        }
        if (stripStack.topControl != top) stripStack.topControl = top;
        strip.layout(true, true);
        layout(true);
    }

    /** The console's selection, or else every line shown with its date, level and loader. */
    private void copy() {
        String selected = console.getSelectionText();
        String text = selected != null && !selected.isEmpty() ? selected : String.join("\n", visible.stream().map(e ->
                String.join("\t", e.time(), e.level().toUpperCase(), e.loader() == null ? "-" : e.loader(), e.text())).toList());
        Clipboards.copyText(getDisplay(), text);
    }
}
