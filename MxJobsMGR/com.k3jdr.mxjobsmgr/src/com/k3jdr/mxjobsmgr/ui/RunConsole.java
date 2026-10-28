package com.k3jdr.mxjobsmgr.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonElement;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.engine.RunEngine;
import com.k3jdr.mxjobsmgr.engine.RunEngine.Level;
import com.k3jdr.mxjobsmgr.engine.RunEngine.LogEntry;
import com.k3jdr.mxjobsmgr.engine.Values;
import com.k3jdr.mxjobsmgr.model.JobNode;

/**
 * The run console under the canvas (RunViews.swift RunConsole): the log, filtered by level, where a click selects the
 * step of an entry; and the output of the selected step as JSON.
 */
public final class RunConsole {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final Level[] FILTER = { Level.DEBUG, Level.INFO, Level.WARNING, Level.ERROR };

    private final Editor editor;
    private final Table table;
    private final Combo filter;
    private final Label outputTitle;
    private final Text output;
    private Level minimum = Level.DEBUG;
    private int shownCount = -1;
    private String outputNode;
    private String shownOutputKey;

    public RunConsole(Composite parent, Editor editor) {
        this.editor = editor;
        SplitPanes split = new SplitPanes(parent, "jobs.console", 300, 4000, 700, 240);
        Composite left = split.leading(), right = split.trailing();

        left.setLayout(Ui.grid(1, 0, 0));
        Composite head = new Composite(left, SWT.NONE);
        head.setLayout(Ui.grid(4, 6, 6));
        head.setLayoutData(Ui.fillH());
        Ui.label(head, "Run Log", Ui.bold()).setLayoutData(Ui.fillH());
        Ui.label(head, "Show:");
        filter = new Combo(head, SWT.READ_ONLY);
        filter.setItems("All", "Info", "Warnings", "Errors");
        filter.select(0);
        filter.addListener(SWT.Selection, e -> {
            minimum = FILTER[Math.max(0, filter.getSelectionIndex())];
            shownCount = -1;
            update();
        });
        QuickHelp.help(filter, "Show", "Which log entries to show: all (with debug), info and up, warnings and errors, or errors only.");
        QuickHelp.help(Ui.button(head, "Clear", () -> editor.run.clearLog()), "Clear", "Clears the run log (the outputs stay).");

        table = new Table(left, SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        table.setLayoutData(Ui.fill());
        table.setHeaderVisible(false);
        table.setFont(Ui.mono());
        for (int w : new int[] { 100, 24, 30, 900 }) new TableColumn(table, SWT.LEFT).setWidth(w);
        table.addListener(SWT.Selection, e -> {
            if (!table.isFocusControl()) return;   // only the user's clicks and keys select a step
            TableItem item = table.getSelection().length == 0 ? null : table.getSelection()[0];
            if (item != null && item.getData() instanceof LogEntry entry && entry.nodeID() != null && editor.space.node(entry.nodeID()) != null) {
                editor.reveal(entry.nodeID());
                outputNode = entry.nodeID();
                updateOutput();
            }
        });
        TableCopy.addMenuItems(table);
        QuickHelp.provider(table, (c, p) -> {
            if (table.getItemCount() == 0) return new QuickHelpItem("Run Log", "Press ▶ Run (Ctrl+R) or Step (F6) to run the jobspace.");
            return JobsHelp.CONSOLE;
        });

        right.setLayout(Ui.grid(1, 0, 0));
        Composite oh = new Composite(right, SWT.NONE);
        oh.setLayout(Ui.grid(2, 6, 6));
        oh.setLayoutData(Ui.fillH());
        Ui.label(oh, "Output", Ui.bold());
        outputTitle = new Label(oh, SWT.NONE);
        outputTitle.setLayoutData(Ui.fillH());
        outputTitle.setForeground(Ui.SECONDARY);
        output = new Text(right, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        output.setFont(Ui.mono());
        output.setLayoutData(Ui.fill());
        QuickHelp.help(output, "Output", "The output of the selected step in the last run, as JSON: what it hands to the steps linked from it.");
        QuickHelp.help(split, JobsHelp.CONSOLE.title(), JobsHelp.CONSOLE.detail(), JobsHelp.CONSOLE.shortcut());
        update();
    }

    /** Adds new log entries and refreshes the output. */
    public void update() {
        if (table.isDisposed()) return;
        List<LogEntry> log = editor.run.log();
        if (log.size() < shownCount || shownCount < 0) {
            table.removeAll();
            shownCount = 0;
        }
        boolean added = false;
        for (int i = shownCount; i < log.size(); i++) {
            LogEntry e = log.get(i);
            if (e.level().ordinal() < minimum.ordinal()) continue;
            TableItem item = new TableItem(table, SWT.NONE);
            item.setData(e);
            JobNode node = editor.space.node(e.nodeID());
            item.setText(new String[] { TIME.format(e.date()), e.level().symbol, node == null ? "" : "L" + node.layer().number, e.message() });
            item.setForeground(1, Colors.of(e.level().rgb));
            if (node != null) item.setForeground(2, Colors.layer(node.layer()));
            if (e.level() == Level.DEBUG) item.setForeground(3, Colors.of(Colors.SECONDARY));
            added = true;
        }
        shownCount = log.size();
        if (added && table.getItemCount() > 0) table.showItem(table.getItem(table.getItemCount() - 1));
        updateOutput();
    }

    public void selectionChanged() {
        outputNode = null;
        updateOutput();
    }

    private void updateOutput() {
        if (output.isDisposed()) return;
        RunEngine run = editor.run;
        String id = outputNode != null ? outputNode : !editor.selectedNodes.isEmpty() ? editor.selectedNodes.iterator().next() : run.currentNode();
        JobNode node = editor.space.node(id);
        JsonElement value = node == null ? null : run.output(node.id);
        RunEngine.NodeState state = node == null ? null : run.state(node.id);
        String key = (node == null ? "" : node.id) + "|" + System.identityHashCode(value) + "|" + state;
        if (Objects.equals(key, shownOutputKey)) return;
        shownOutputKey = key;
        outputTitle.setText(node == null ? "" : Ui.escape(node.title + (state != null ? "  " + state.symbol + " " + state.title : "")));
        if (state != null) outputTitle.setForeground(Colors.of(state.rgb));
        output.setText(node == null ? "Select a step to see its output." : value == null ? "No output yet." : Values.pretty(value));
    }
}
