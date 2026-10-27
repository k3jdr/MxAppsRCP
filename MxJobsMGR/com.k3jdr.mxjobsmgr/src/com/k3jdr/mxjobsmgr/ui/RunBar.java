package com.k3jdr.mxjobsmgr.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.engine.RunEngine;
import com.k3jdr.mxjobsmgr.engine.RunEngine.Phase;
import com.k3jdr.mxjobsmgr.engine.Validator;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.Specs.AgentsAction;
import com.k3jdr.mxjobsmgr.model.Specs.DataAction;
import com.k3jdr.mxjobsmgr.model.Specs.OperationsAction;
import com.k3jdr.mxjobsmgr.model.Specs.RunSettings;

/**
 * The run bar above the canvas (RunViews.swift RunBar) — run controls, what each layer does in a run, breakpoints,
 * options and the run's status — with the Mac window toolbar's zoom, Problems, API Subscriptions, Inspector and
 * console buttons at its right end.
 */
public final class RunBar {
    private final Editor editor;
    final Composite root;
    private final ToolBar controls, layers, tools;
    private final ToolItem runItem, pauseItem, stepItem, stopItem, l1, l2, l3, breakpoints, options;
    private final ToolItem zoomOut, zoomLabel, zoomIn, problems, subscriptions, inspectorItem, consoleItem;
    private final Label status;

    public RunBar(Composite parent, Editor editor) {
        this.editor = editor;
        root = new Composite(parent, SWT.NONE);
        org.eclipse.swt.layout.RowLayout rows = new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL);
        rows.wrap = true;
        rows.center = true;
        rows.marginLeft = rows.marginRight = 6;
        rows.marginTop = rows.marginBottom = 3;
        rows.spacing = 8;
        root.setLayout(rows);
        root.setBackground(Ui.SIDEBAR);

        controls = new ToolBar(root, SWT.FLAT | SWT.RIGHT);
        runItem = item(controls, "▶", () -> editor.startRun());
        pauseItem = item(controls, "⏸", () -> {
            if (editor.run.phase() == Phase.RUNNING) editor.run.pause();
            else editor.run.continueRun();
        });
        stepItem = item(controls, "↳", editor::stepRun);
        stopItem = item(controls, "■", () -> editor.run.stop());
        QuickHelp.provider(controls, (c, p) -> help(controls, p));

        layers = new ToolBar(root, SWT.FLAT | SWT.RIGHT);
        l1 = layerMenu(Layer.OPERATIONS);
        l2 = layerMenu(Layer.AGENTS);
        l3 = layerMenu(Layer.DATA);
        new ToolItem(layers, SWT.SEPARATOR);
        breakpoints = new ToolItem(layers, SWT.CHECK);
        breakpoints.setText("⏵");
        breakpoints.addListener(SWT.Selection, e -> editor.toggleBreakpoints());
        options = new ToolItem(layers, SWT.DROP_DOWN);
        options.setText("⚙");
        options.addListener(SWT.Selection, e -> showOptions());
        QuickHelp.provider(layers, (c, p) -> help(layers, p));

        status = new Label(root, SWT.NONE);
        status.setLayoutData(new org.eclipse.swt.layout.RowData(230, SWT.DEFAULT));
        status.setBackground(Ui.SIDEBAR);
        QuickHelp.help(status, "Run Status", "Ready, running, paused, waiting for you, finished, failed or stopped — with the current step, or how long the run took.");

        tools = new ToolBar(root, SWT.FLAT | SWT.RIGHT);
        zoomOut = item(tools, "−", editor::zoomOut);
        zoomLabel = item(tools, "100%", editor::actualSize);
        zoomIn = item(tools, "+", editor::zoomIn);
        new ToolItem(tools, SWT.SEPARATOR);
        problems = item(tools, "No Problems", () -> editor.main.showProblems(editor));
        subscriptions = item(tools, "🔑", () -> editor.main.showSubscriptions(editor, null));
        consoleItem = new ToolItem(tools, SWT.CHECK);
        consoleItem.setText("▤");
        consoleItem.addListener(SWT.Selection, e -> editor.setConsoleShown(consoleItem.getSelection()));
        inspectorItem = new ToolItem(tools, SWT.CHECK);
        inspectorItem.setText("◨");
        inspectorItem.addListener(SWT.Selection, e -> editor.main.setInspectorShown(inspectorItem.getSelection()));
        QuickHelp.provider(tools, (c, p) -> help(tools, p));
        QuickHelp.help(root, JobsHelp.RUN_BAR.title(), JobsHelp.RUN_BAR.detail());
        Ui.background(root, Ui.SIDEBAR);
    }

    private ToolItem item(ToolBar bar, String text, Runnable action) {
        ToolItem item = new ToolItem(bar, SWT.PUSH);
        item.setText(text);
        item.addListener(SWT.Selection, e -> action.run());
        return item;
    }

    private ToolItem layerMenu(Layer layer) {
        ToolItem item = new ToolItem(layers, SWT.DROP_DOWN);
        item.setData(layer);
        item.addListener(SWT.Selection, e -> showLayerMenu(item, layer));
        return item;
    }

    private void popup(ToolItem item, Menu menu) {
        Rectangle r = item.getBounds();
        Point p = item.getParent().toDisplay(r.x, r.y + r.height);
        menu.setLocation(p);
        menu.setVisible(true);
    }

    private void showLayerMenu(ToolItem item, Layer layer) {
        Menu menu = new Menu(root.getShell(), SWT.POP_UP);
        MenuItem head = new MenuItem(menu, SWT.PUSH);
        head.setText("Layer " + layer.number + " · " + layer.title);
        head.setEnabled(false);
        new MenuItem(menu, SWT.SEPARATOR);
        RunSettings s = editor.space.run;
        switch (layer) {
            case OPERATIONS -> {
                for (OperationsAction a : OperationsAction.values()) radio(menu, a.title, s.operations == a, () -> s.operations = a);
            }
            case AGENTS -> {
                for (AgentsAction a : AgentsAction.values()) radio(menu, a.title, s.agents == a, () -> s.agents = a);
            }
            case DATA -> {
                for (DataAction a : DataAction.values()) radio(menu, a.title, s.data == a, () -> s.data = a);
            }
        }
        popup(item, menu);
    }

    private void radio(Menu menu, String text, boolean selected, Runnable choose) {
        MenuItem mi = new MenuItem(menu, SWT.RADIO);
        mi.setText(text);
        mi.setSelection(selected);
        mi.addListener(SWT.Selection, e -> {
            if (!mi.getSelection() || selected) return;
            editor.willChange(null);
            choose.run();
            editor.changed();
        });
    }

    private void showOptions() {
        Menu menu = new Menu(root.getShell(), SWT.POP_UP);
        RunSettings s = editor.space.run;
        MenuItem stop = new MenuItem(menu, SWT.CHECK);
        stop.setText("Stop the run when a step fails");
        stop.setSelection(s.stopOnError);
        stop.addListener(SWT.Selection, e -> {
            editor.willChange(null);
            s.stopOnError = stop.getSelection();
            editor.changed();
        });
        MenuItem delayItem = new MenuItem(menu, SWT.CASCADE);
        delayItem.setText("Step delay");
        Menu delays = new Menu(delayItem);
        delayItem.setMenu(delays);
        double[] values = { 0, 0.2, 0.4, 1, 2 };
        String[] names = { "No delay", "0.2 s", "0.4 s", "1 s", "2 s" };
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            radio(delays, names[i], Math.abs(s.stepDelay - v) < 1e-9, () -> s.stepDelay = v);
        }
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem clear = new MenuItem(menu, SWT.PUSH);
        clear.setText("Clear Run Results");
        clear.setEnabled(!editor.run.isActive());
        clear.addListener(SWT.Selection, e -> editor.run.reset());
        popup(options, menu);
    }

    /** Shows the run's state, the layer settings, and the toolbar's counts. */
    public void update() {
        if (root.isDisposed()) return;
        RunEngine run = editor.run;
        Phase phase = run.phase();
        runItem.setText(run.canContinue() ? "▶ Continue" : "▶");
        runItem.setEnabled(!(run.isActive() && !run.canContinue()));
        runItem.setToolTipText(run.canContinue() ? "Continue (F8)" : "Run (Ctrl+R)");
        pauseItem.setText(phase == Phase.PAUSED ? "⏵" : "⏸");
        pauseItem.setEnabled(phase == Phase.RUNNING || phase == Phase.PAUSED);
        pauseItem.setToolTipText(phase == Phase.PAUSED ? "Continue (F8)" : "Pause before the next step");
        stepItem.setEnabled(phase != Phase.RUNNING && phase != Phase.WAITING);
        stepItem.setToolTipText("Step — run to the next step and pause (F6)");
        stopItem.setEnabled(run.isActive());
        stopItem.setToolTipText("Stop (Ctrl+.)");
        RunSettings s = editor.space.run;
        l1.setText("L1 " + s.operations.shortTitle);
        l2.setText("L2 " + s.agents.shortTitle);
        l3.setText("L3 " + s.data.shortTitle);
        for (ToolItem t : new ToolItem[] { l1, l2, l3 }) {
            Layer layer = (Layer) t.getData();
            t.setToolTipText("What a run does with Layer " + layer.number + " · " + layer.title);
        }
        breakpoints.setSelection(s.breakpointsEnabled);
        breakpoints.setToolTipText("Breakpoints on / off (Ctrl+Y). Toggle one on the selected step with Ctrl+\\.");
        options.setToolTipText("Run options");

        String current = "";
        JobNode node = editor.space.node(run.currentNode());
        if (node != null) current = " — " + node.title;
        String text = switch (phase) {
            case IDLE -> "Ready";
            case RUNNING -> "⟳ Running" + current;
            case PAUSED -> "⏸ Paused" + current;
            case WAITING -> "? Waiting for you" + current;
            case FINISHED -> "✔ Finished" + run.duration();
            case FAILED -> "✖ Failed" + run.duration();
            case STOPPED -> "■ Stopped" + run.duration();
        };
        status.setText(Ui.escape(text));
        status.setToolTipText(text);
        status.setForeground(Colors.of(switch (phase) {
            case IDLE -> Colors.SECONDARY;
            case RUNNING -> Colors.ACCENT;
            case PAUSED -> 0x9B59B6;
            case WAITING -> 0xE08600;
            case FINISHED -> 0x2E9E48;
            case FAILED -> 0xD03030;
            case STOPPED -> Colors.SECONDARY;
        }));

        zoomLabel.setText(Math.round(editor.zoom * 100) + "%");
        zoomLabel.setToolTipText("Click for 100% (Ctrl+0)");
        zoomOut.setToolTipText("Zoom Out (Ctrl+-)");
        zoomIn.setToolTipText("Zoom In (Ctrl+=)");
        int n = editor.issues.size();
        boolean error = editor.issues.stream().anyMatch(i -> i.severity() == Validator.Severity.ERROR);
        problems.setText(n == 0 ? "✔ No Problems" : (error ? "⛔ " : "⚠ ") + n + " Problem" + (n == 1 ? "" : "s"));
        tools.pack();
        controls.pack();
        layers.pack();
        problems.setToolTipText("Configuration checks (Ctrl+Shift+K)");
        subscriptions.setToolTipText("Layer 3 API subscriptions and their secure accounts (Ctrl+Alt+K)");
        consoleItem.setSelection(editor.isConsoleShown());
        consoleItem.setToolTipText("Show or hide the run console (Ctrl+Shift+Y)");
        inspectorItem.setSelection(editor.main.inspectorShown());
        inspectorItem.setToolTipText("Show or hide the properties panel (Ctrl+Alt+I)");
        root.getParent().layout(true, true);
    }

    private QuickHelpItem help(ToolBar bar, Point p) {
        ToolItem item = bar.getItem(p);
        if (item == null) return null;
        RunEngine run = editor.run;
        if (item == runItem) return run.canContinue()
                ? new QuickHelpItem("Continue", "Carries on from where the run paused, to the next breakpoint or the end.", "F8")
                : new QuickHelpItem("Run", "Runs the jobspace from its trigger, step by step down and back up the layers, following each layer's setting in this bar.", "Ctrl+R");
        if (item == pauseItem) return run.phase() == Phase.PAUSED
                ? new QuickHelpItem("Continue", "Carries on from where the run paused.", "F8")
                : new QuickHelpItem("Pause", "Pauses the run before its next step; Continue or Step goes on.");
        if (item == stepItem) return new QuickHelpItem("Step", "Runs the next step and pauses, so you can look at its output in the console before going on.", "F6");
        if (item == stopItem) return new QuickHelpItem("Stop", "Stops the run. Its results stay on the canvas and in the console until you run again or clear them.", "Ctrl+.");
        if (item.getData() instanceof Layer layer) return JobsHelp.layerAction(layer);
        if (item == breakpoints) return new QuickHelpItem("Breakpoints", "Turns all breakpoints on or off. A run pauses before each step with a breakpoint while they're on; Ctrl+\\ toggles one on the selected step.", "Ctrl+Y");
        if (item == options) return new QuickHelpItem("Run Options", "Stop the run when a step fails, a delay between steps to watch it go, and Clear Run Results.");
        if (item == zoomOut) return new QuickHelpItem("Zoom Out", "Makes the diagram smaller (down to 40%).", "Ctrl+-");
        if (item == zoomLabel) return new QuickHelpItem("Zoom", "The diagram's zoom. Click for actual size; Ctrl+wheel on the canvas zooms too.", "Ctrl+0");
        if (item == zoomIn) return new QuickHelpItem("Zoom In", "Makes the diagram bigger (up to 200%).", "Ctrl+=");
        if (item == problems) return new QuickHelpItem("Problems", "Configuration checks: missing subscriptions or keys, unlinked steps, empty settings. Click one to go to the step or subscription.", "Ctrl+Shift+K");
        if (item == subscriptions) return JobsHelp.SUBSCRIPTIONS;
        if (item == consoleItem) return new QuickHelpItem("Run Console", "Shows or hides the run log and step outputs under the canvas.", "Ctrl+Shift+Y");
        if (item == inspectorItem) return new QuickHelpItem("Inspector", "Shows or hides the properties panel on the right: the selected step's settings, or the jobspace's.", "Ctrl+Alt+I");
        return null;
    }

    /** Where the Problems button is, for the popup under it. */
    public Point problemsAnchor() {
        Rectangle r = problems.getBounds();
        return tools.toDisplay(r.x, r.y + r.height);
    }
}
