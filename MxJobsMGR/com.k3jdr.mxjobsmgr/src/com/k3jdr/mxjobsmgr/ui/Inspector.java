package com.k3jdr.mxjobsmgr.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.engine.ApiTester;
import com.k3jdr.mxjobsmgr.engine.PythonRunner;
import com.k3jdr.mxjobsmgr.engine.RunEngine;
import com.k3jdr.mxjobsmgr.engine.SecureKeys;
import com.k3jdr.mxjobsmgr.engine.Validator;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobLink;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.AgentSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ApiCallSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ApprovalChannel;
import com.k3jdr.mxjobsmgr.model.Specs.ApprovalSpec;
import com.k3jdr.mxjobsmgr.model.Specs.Argument;
import com.k3jdr.mxjobsmgr.model.Specs.ArgumentType;
import com.k3jdr.mxjobsmgr.model.Specs.AssertionOp;
import com.k3jdr.mxjobsmgr.model.Specs.AuthStyle;
import com.k3jdr.mxjobsmgr.model.Specs.ExceptionPolicy;
import com.k3jdr.mxjobsmgr.model.Specs.ExecutionMode;
import com.k3jdr.mxjobsmgr.model.Specs.ExecutionSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ExpectedOutcome;
import com.k3jdr.mxjobsmgr.model.Specs.FailureAction;
import com.k3jdr.mxjobsmgr.model.Specs.HttpMethod;
import com.k3jdr.mxjobsmgr.model.Specs.KeyValue;
import com.k3jdr.mxjobsmgr.model.Specs.ReportSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ScheduleMode;
import com.k3jdr.mxjobsmgr.model.Specs.ScheduleSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ScriptSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ValueAssertion;

/**
 * The properties panel on the right (InspectorView.swift). With a node selected: its general settings, the kind's own
 * settings, arguments, expected outcome and exception handling, its checks and last run. With a link: its label.
 * With nothing selected: the jobspace — name, description, layer counts, subscriptions and checks.
 */
public final class Inspector {
    private final Editor editor;
    private final ScrolledComposite scroller;
    private Composite content;
    /** Test Call / Run Script results, by node, kept while the window is open. */
    private final Map<String, ApiTester.Result> results = new HashMap<>();
    private final java.util.Set<String> testing = new java.util.HashSet<>();
    private String builtFor = "";

    public Inspector(Composite parent, Editor editor) {
        this.editor = editor;
        scroller = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.BORDER);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        scroller.addListener(SWT.Resize, e -> relayout());
        QuickHelp.provider(scroller, (c, p) -> JobsHelp.inspector(editor));
    }

    public boolean hasFocus() {
        Control f = scroller.getDisplay().getFocusControl();
        for (Control c = f; c != null; c = c.getParent()) if (c == scroller) return true;
        return false;
    }

    private void relayout() {
        if (content == null || content.isDisposed()) return;
        int width = scroller.getClientArea().width;
        Point size = content.computeSize(Math.max(260, width), SWT.DEFAULT);
        scroller.setMinSize(size);
        content.layout(true, true);
    }

    /** Builds the panel again for the selection (keeping the scroll position when it is the same thing). */
    public void rebuild() {
        if (scroller.isDisposed()) return;
        String key = editor.selectedNodes + "|" + editor.selectedLink;
        Point origin = key.equals(builtFor) ? scroller.getOrigin() : new Point(0, 0);
        builtFor = key;
        scroller.setRedraw(false);
        try {
            if (content != null) content.dispose();
            content = new Composite(scroller, SWT.NONE);
            content.setLayout(Ui.grid(1, 8, 8));
            scroller.setContent(content);
            Jobspace space = editor.space;
            if (editor.selectedNodes.size() == 1 && space.node(editor.selectedNodes.iterator().next()) != null) {
                buildNode(space.node(editor.selectedNodes.iterator().next()));
            } else if (editor.selectedNodes.size() > 1) {
                Ui.label(content, editor.selectedNodes.size() + " Nodes Selected", Ui.subheading());
                Ui.wrap(content, "Drag to move them together, Ctrl+D to duplicate, Delete to remove.").setForeground(Ui.SECONDARY);
            } else if (space.link(editor.selectedLink) != null) {
                buildLink(space.link(editor.selectedLink));
            } else {
                buildJobspace();
            }
            relayout();
            scroller.setOrigin(origin);
        } finally {
            scroller.setRedraw(true);
        }
    }

    private void rebuildLater() {
        scroller.getDisplay().asyncExec(() -> {
            if (!scroller.isDisposed()) rebuild();
        });
    }

    // MARK: Form helpers

    private Group section(String title) {
        Group g = new Group(content, SWT.NONE);
        g.setText(title);
        g.setLayout(Ui.grid(2, 8, 5));
        g.setLayoutData(Ui.fillH());
        QuickHelpItem help = JobsHelp.section(title);
        if (help != null) QuickHelp.help(g, help.title(), help.detail());
        return g;
    }

    private static GridData wide(int span) {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1);
        gd.widthHint = 120;
        return gd;
    }

    private Label caption(Composite g, String text) {
        Label l = Ui.label(g, text);
        l.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        return l;
    }

    private void footer(Composite g, String text) {
        Label l = new Label(g, SWT.WRAP);
        l.setText(Ui.escape(text));
        l.setForeground(Ui.SECONDARY);
        l.setFont(Ui.small());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.widthHint = 200;
        l.setLayoutData(gd);
    }

    /** A value edit: remembered for Undo (typing coalesced per field), then the document is changed. */
    private void edit(String key, Runnable change, boolean structural) {
        editor.willChange(key);
        change.run();
        if (structural) {
            editor.changed();
            rebuildLater();
        } else {
            editor.changed();
        }
    }

    private Text text(Composite g, String label, String value, String prompt, Consumer<String> set) {
        if (label != null) caption(g, label);
        Text t = new Text(g, SWT.BORDER | SWT.SINGLE);
        t.setText(value == null ? "" : value);
        if (prompt != null) t.setMessage(prompt);
        t.setLayoutData(wide(label != null ? 1 : 2));
        String key = "text:" + builtFor + ":" + label + ":" + System.identityHashCode(set);
        t.addListener(SWT.Modify, e -> edit(key, () -> set.accept(t.getText()), false));
        return t;
    }

    private Text multi(Composite g, String label, String value, String prompt, int lines, boolean mono, Consumer<String> set) {
        if (label != null) {
            Label l = caption(g, label);
            l.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false, 2, 1));
        }
        Text t = new Text(g, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        t.setText(value == null ? "" : value);
        if (prompt != null) t.setMessage(prompt);
        if (mono) t.setFont(Ui.mono());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.widthHint = 120;
        gd.heightHint = t.getLineHeight() * lines + 6;
        t.setLayoutData(gd);
        String key = "multi:" + builtFor + ":" + label + ":" + System.identityHashCode(set);
        t.addListener(SWT.Modify, e -> edit(key, () -> set.accept(t.getText()), false));
        return t;
    }

    private Combo combo(Composite g, String label, String[] items, int selected, boolean structural, IntConsumer set) {
        if (label != null) caption(g, label);
        Combo c = new Combo(g, SWT.READ_ONLY);
        c.setItems(items);
        c.select(Math.max(0, selected));
        c.setLayoutData(wide(label != null ? 1 : 2));
        c.addListener(SWT.Selection, e -> edit(null, () -> set.accept(c.getSelectionIndex()), structural));
        return c;
    }

    private Button check(Composite g, String label, boolean value, boolean structural, Consumer<Boolean> set) {
        Button b = new Button(g, SWT.CHECK);
        b.setText(Ui.escape(label));
        b.setSelection(value);
        b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        b.addListener(SWT.Selection, e -> edit(null, () -> set.accept(b.getSelection()), structural));
        return b;
    }

    private Spinner spinner(Composite g, String label, int value, int min, int max, int step, IntConsumer set) {
        caption(g, label);
        Spinner s = new Spinner(g, SWT.BORDER);
        s.setValues(Math.max(min, Math.min(max, value)), min, max, 0, step, step * 10);
        s.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        String key = "spin:" + builtFor + ":" + label;
        s.addListener(SWT.Modify, e -> edit(key, () -> set.accept(s.getSelection()), false));
        return s;
    }

    private Text info(Composite g, String label, String value) {
        caption(g, label);
        Text t = Ui.selectable(g, value, SWT.NONE);
        t.setLayoutData(wide(1));
        return t;
    }

    private Button button(Composite g, String text, Runnable action) {
        Button b = Ui.button(g, text, action);
        b.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
        return b;
    }

    private static <E extends Enum<E>> String[] titles(E[] values, java.util.function.Function<E, String> title) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = title.apply(values[i]);
        return out;
    }

    /** A remove button for list rows. */
    private void remove(Composite row, Runnable action) {
        Button b = new Button(row, SWT.PUSH | SWT.FLAT);
        b.setText("−");
        b.setToolTipText("Remove");
        b.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        b.addListener(SWT.Selection, e -> edit(null, action, true));
    }

    private Composite row(Composite g, int columns) {
        Composite r = new Composite(g, SWT.NONE);
        r.setLayout(Ui.grid(columns, 0, 4));
        r.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        return r;
    }

    private Text rowText(Composite r, String value, String prompt, String key, Consumer<String> set) {
        Text t = new Text(r, SWT.BORDER);
        t.setText(value);
        t.setMessage(prompt);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 60;
        t.setLayoutData(gd);
        t.addListener(SWT.Modify, e -> edit(key, () -> set.accept(t.getText()), false));
        return t;
    }

    private void keyValues(Composite g, List<KeyValue> items, String keyPrompt, String valuePrompt, String addTitle) {
        for (KeyValue kv : items) {
            Composite r = row(g, 3);
            rowText(r, kv.key, keyPrompt, "kv:" + kv.id + ":k", v -> kv.key = v);
            rowText(r, kv.value, valuePrompt, "kv:" + kv.id + ":v", v -> kv.value = v);
            remove(r, () -> items.remove(kv));
        }
        button(g, "+ " + addTitle, () -> edit(null, () -> items.add(new KeyValue()), true));
    }

    private void strings(Composite g, List<String> items, String prompt, String addTitle, List<String> suggestions) {
        for (int i = 0; i < items.size(); i++) {
            int index = i;
            Composite r = row(g, 2);
            rowText(r, items.get(i), prompt, "str:" + System.identityHashCode(items) + ":" + i, v -> {
                if (index < items.size()) items.set(index, v);
            });
            remove(r, () -> {
                if (index < items.size()) items.remove(index);
            });
        }
        Composite r = row(g, 2);
        Ui.button(r, "+ " + addTitle, () -> edit(null, () -> items.add(""), true));
        List<String> missing = suggestions.stream().filter(s -> !items.contains(s)).toList();
        if (!missing.isEmpty()) {
            Button linked = new Button(r, SWT.PUSH);
            linked.setText("Linked ▾");
            linked.setToolTipText("Add the output name of a step linked into this one");
            linked.addListener(SWT.Selection, e -> {
                Menu m = new Menu(linked);
                for (String s : missing) {
                    MenuItem mi = new MenuItem(m, SWT.PUSH);
                    mi.setText(Ui.escape(s));
                    mi.addListener(SWT.Selection, x -> edit(null, () -> items.add(s), true));
                }
                Point p = linked.toDisplay(0, linked.getSize().y);
                m.setLocation(p);
                m.setVisible(true);
            });
        }
    }

    // MARK: Jobspace

    private void buildJobspace() {
        Jobspace space = editor.space;
        Group js = section("Jobspace");
        text(js, "Name", space.name, null, v -> space.name = v);
        multi(js, "Description", space.description, null, 4, false, v -> space.description = v);

        Group layers = section("Layers");
        for (Layer layer : Layer.values()) {
            Label l = caption(layers, "■ " + layer.number + " · " + layer.title);
            l.setForeground(Colors.layer(layer));
            long count = space.nodes.stream().filter(n -> n.layer() == layer).count();
            Ui.label(layers, count + " nodes");
        }
        caption(layers, "Links");
        Ui.label(layers, Integer.toString(space.links.size()));

        Group subs = section("Layer 3 API Subscriptions");
        if (space.subscriptions.isEmpty()) {
            Label none = Ui.label(subs, "No subscriptions yet");
            none.setForeground(Ui.SECONDARY);
            none.setLayoutData(wide(2));
        }
        for (ApiSubscription sub : space.subscriptions) {
            Composite c = new Composite(subs, SWT.NONE);
            c.setLayout(Ui.grid(1, 0, 0));
            c.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Ui.label(c, sub.name);
            Label url = Ui.label(c, sub.baseURL);
            url.setFont(Ui.small());
            url.setForeground(Ui.SECONDARY);
            keyStatus(subs, sub);
        }
        button(subs, "Manage API Subscriptions…", () -> editor.main.showSubscriptions(editor, null));

        Group checks = section("Checks");
        issues(checks, editor.issues);
    }

    /** A subscription's key status (KeyStatus in the Mac app). */
    static String[] keyStatus(ApiSubscription sub) {
        if (sub.auth == AuthStyle.NONE) return new String[] { "No key needed", "secondary" };
        if (sub.auth == AuthStyle.LOCAL_SERVICE)
            return new String[] { SecureKeys.hasKey(sub) ? "Local · token in Secure Accounts" : "Local · no token", "secondary" };
        if (SecureKeys.hasKey(sub)) return new String[] { "🔑 Key in Secure Accounts", "green" };
        if (sub.secureAccountID == null) return new String[] { "No secure account", "orange" };
        return new String[] { "No key", "orange" };
    }

    private Label keyStatus(Composite g, ApiSubscription sub) {
        String[] s = keyStatus(sub);
        Label l = Ui.label(g, s[0]);
        l.setFont(Ui.small());
        l.setForeground(s[1].equals("green") ? Ui.GREEN : s[1].equals("orange") ? Ui.ORANGE : Ui.SECONDARY);
        l.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        return l;
    }

    private void issues(Composite g, List<Validator.Issue> list) {
        if (list.isEmpty()) {
            Label ok = Ui.label(g, "✔ No problems found");
            ok.setForeground(Ui.GREEN);
            ok.setLayoutData(wide(2));
            return;
        }
        for (Validator.Issue issue : list) {
            Label icon = Ui.label(g, issue.severity().symbol);
            icon.setForeground(Colors.of(issue.severity().rgb));
            icon.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false));
            Label msg = new Label(g, SWT.WRAP);
            msg.setText(Ui.escape(issue.message()));
            GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
            gd.widthHint = 150;
            msg.setLayoutData(gd);
            msg.setCursor(msg.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
            Runnable go = () -> editor.main.goToIssue(editor, issue);
            msg.addListener(SWT.MouseUp, e -> go.run());
            icon.addListener(SWT.MouseUp, e -> go.run());
        }
    }

    // MARK: Link

    private void buildLink(JobLink link) {
        Jobspace space = editor.space;
        Group g = section("Link");
        JobNode from = space.node(link.from), to = space.node(link.to);
        info(g, "From", from != null ? from.title : "–");
        info(g, "To", to != null ? to.title : "–");
        text(g, "Label", link.label, "e.g. prices, approved", v -> link.label = v);
        footer(g, "Links pointing down the layers are calls; links pointing up (dashed) carry results back. Press Delete to remove the link.");
    }

    // MARK: Node

    private void buildNode(JobNode node) {
        Jobspace space = editor.space;
        Composite head = new Composite(content, SWT.NONE);
        head.setLayout(Ui.grid(2, 0, 8));
        head.setLayoutData(Ui.fillH());
        Label sym = Ui.label(head, node.kind.symbol, Ui.font(6, SWT.BOLD));
        sym.setForeground(Colors.layer(node.layer()));
        Composite ht = new Composite(head, SWT.NONE);
        ht.setLayout(Ui.grid(1, 0, 0));
        Ui.label(ht, node.kind.title, Ui.subheading());
        Label lay = Ui.label(ht, "Layer " + node.layer().number + " · " + node.layer().title);
        lay.setForeground(Colors.layer(node.layer()));
        QuickHelp.help(head, node.kind.title, JobsHelp.KIND_DETAIL.getOrDefault(node.kind, node.kind.summary));

        Group step = section("Step");
        text(step, "Title", node.title, null, v -> node.title = v);
        check(step, "Enabled", node.enabled, false, v -> node.enabled = v);
        check(step, "Breakpoint — pause runs before this step", node.breakpoint, false, v -> node.breakpoint = v);
        multi(step, "Notes", node.notes, null, 3, false, v -> node.notes = v);

        List<Validator.Issue> own = editor.issues.stream().filter(i -> node.id.equals(i.nodeID())).toList();
        if (!own.isEmpty()) issues(section("Checks"), own);

        RunEngine.NodeState state = editor.run.state(node.id);
        if (state != null && state != RunEngine.NodeState.PENDING) {
            Group last = section("Last Run");
            Label s = Ui.label(last, state.symbol + " " + state.title);
            s.setForeground(Colors.of(state.rgb));
            s.setLayoutData(wide(2));
            String message = null;
            List<RunEngine.LogEntry> log = editor.run.log();
            for (int i = log.size() - 1; i >= 0 && message == null; i--) {
                RunEngine.LogEntry e = log.get(i);
                if (node.id.equals(e.nodeID()) && e.level().ordinal() >= RunEngine.Level.SUCCESS.ordinal()) message = e.message();
            }
            for (int i = log.size() - 1; i >= 0 && message == null; i--) if (node.id.equals(log.get(i).nodeID())) message = log.get(i).message();
            if (message != null) {
                Text m = Ui.selectable(last, message, SWT.NONE);
                m.setForeground(Ui.SECONDARY);
                GridData gd = wide(2);
                m.setLayoutData(gd);
            }
            button(last, "Show Output in Console", () -> editor.setConsoleShown(true));
        }

        kindSection(node, space);

        Group args = section("Arguments");
        for (Argument arg : node.arguments) {
            Composite r = row(args, 3);
            rowText(r, arg.name, "name", "arg:" + arg.id + ":n", v -> arg.name = v);
            Combo type = new Combo(r, SWT.READ_ONLY);
            type.setItems(titles(ArgumentType.values(), ArgumentType::raw));
            type.select(arg.type.ordinal());
            type.addListener(SWT.Selection, e -> edit(null, () -> arg.type = ArgumentType.values()[type.getSelectionIndex()], true));
            remove(r, () -> node.arguments.remove(arg));
            Composite r2 = row(args, 2);
            rowText(r2, arg.value, arg.type == ArgumentType.SYMBOLS ? "AAPL,MSFT" : "value", "arg:" + arg.id + ":v", v -> arg.value = v);
            Button req = new Button(r2, SWT.CHECK);
            req.setText("Required");
            req.setSelection(arg.required);
            req.addListener(SWT.Selection, e -> edit(null, () -> arg.required = req.getSelection(), false));
        }
        button(args, "+ Add Argument", () -> edit(null, () -> node.arguments.add(new Argument()), true));
        footer(args, "Use {{name}} in paths, parameters, headers and templates. Built-ins: {{today}}, {{yesterday}}.");

        outcomeSection(node, space);
        exceptionSection(node.exceptions);
    }

    private void kindSection(JobNode node, Jobspace space) {
        switch (node.kind) {
            case TRIGGER -> {
                ScheduleSpec spec = node.schedule();
                Group g = section("Schedule");
                combo(g, "Runs", titles(ScheduleMode.values(), m -> m.title), spec.mode.ordinal(), true, i -> spec.mode = ScheduleMode.values()[i]);
                if (spec.mode == ScheduleMode.DAILY || spec.mode == ScheduleMode.WEEKDAYS) {
                    text(g, "Time (HH:mm)", spec.time, null, v -> spec.time = v);
                    text(g, "Time zone", spec.timeZone, null, v -> spec.timeZone = v);
                }
                if (spec.mode == ScheduleMode.INTERVAL) spinner(g, "Every N minutes", spec.intervalMinutes, 1, 1440, 5, v -> spec.intervalMinutes = v);
            }
            case INSTRUCTION_SET, AGENT_TASK, AGGREGATE, DECISION -> {
                AgentSpec spec = node.agent();
                String title = node.kind == NodeKind.INSTRUCTION_SET ? "Instruction Assembly" : node.kind == NodeKind.DECISION ? "Decision" : "Agent";
                Group g = section(title);
                multi(g, node.kind == NodeKind.INSTRUCTION_SET ? "Assembly instructions" : "Instructions", spec.instructions, null, 5, false, v -> spec.instructions = v);
                if (node.kind != NodeKind.INSTRUCTION_SET) text(g, "Model / engine", spec.model, "e.g. claude-sonnet-5-5", v -> spec.model = v);
                text(g, "Output format", spec.outputFormat, "e.g. JSON array of instructions", v -> spec.outputFormat = v);
                caption(g, "Inputs").setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
                List<String> available = space.inputNames(node.id);
                strings(g, spec.inputs, "input name", "Add Input", available);
                if (!available.isEmpty()) footer(g, "Linked inputs: " + String.join(", ", available));
            }
            case APPROVAL_GATE -> {
                ApprovalSpec spec = node.approval();
                Group g = section("User Approval");
                text(g, "Approver", spec.approver, null, v -> spec.approver = v);
                combo(g, "Ask via", titles(ApprovalChannel.values(), c -> c.title), spec.channel.ordinal(), false, i -> spec.channel = ApprovalChannel.values()[i]);
                spinner(g, "Expires after (min)", spec.expiresAfterMinutes, 5, 1440, 5, v -> spec.expiresAfterMinutes = v);
                check(g, "Require a comment", spec.requireComment, false, v -> spec.requireComment = v);
                multi(g, "Summary shown to the approver", spec.summaryTemplate, null, 3, false, v -> spec.summaryTemplate = v);
                footer(g, "Nothing downstream of this step runs until the user approves.");
            }
            case EXECUTE_REQUEST -> {
                ExecutionSpec spec = node.execution();
                Group g = section("Execution Request");
                text(g, "Target", spec.target, "e.g. Broker API", v -> spec.target = v);
                combo(g, "Mode", titles(ExecutionMode.values(), m -> m.title), spec.mode.ordinal(), true, i -> spec.mode = ExecutionMode.values()[i]);
                check(g, "Confirm again before sending", spec.confirmBeforeSend, false, v -> spec.confirmBeforeSend = v);
                if (spec.mode == ExecutionMode.LIVE) {
                    Label warn = Ui.label(g, "⚠ Live mode sends real requests once approved.");
                    warn.setForeground(Ui.ORANGE);
                    warn.setLayoutData(wide(2));
                }
            }
            case OUTCOME_REPORT -> {
                ReportSpec spec = node.report();
                Group g = section("Outcome Report");
                text(g, "Recipients", spec.recipients, null, v -> spec.recipients = v);
                check(g, "Detailed report", spec.detailed, false, v -> spec.detailed = v);
                check(g, "Include failures and data gaps", spec.includeFailures, false, v -> spec.includeFailures = v);
            }
            case API_READ -> apiSection(node, space);
            case PYTHON_SCRIPT -> scriptSection(node, space);
            case DATA_NORMALIZE -> {
                Group g = section("Field Mappings");
                keyValues(g, node.transform().mappings, "output field", "JSON path", "Add Mapping");
                footer(g, "Each output field takes the value at a dotted JSON path of the incoming data.");
            }
        }
    }

    private String[] subscriptionNames(Jobspace space) {
        List<String> names = new ArrayList<>();
        names.add("None");
        for (ApiSubscription s : space.subscriptions) names.add(s.name);
        return names.toArray(String[]::new);
    }

    private int subscriptionIndex(Jobspace space, String id) {
        for (int i = 0; i < space.subscriptions.size(); i++) if (space.subscriptions.get(i).id.equals(id)) return i + 1;
        return 0;
    }

    private void apiSection(JobNode node, Jobspace space) {
        ApiCallSpec api = node.api();
        ApiSubscription sub = space.subscription(api.subscriptionID);
        ServiceCatalog.Service service = Editor.serviceFor(sub);
        if (service != null) {
            Group about = section("About This Service");
            Label name = Ui.label(about, service.language() + "  " + service.name(), Ui.bold());
            name.setLayoutData(wide(2));
            Text purpose = Ui.selectable(about, service.purpose(), SWT.NONE);
            GridData pg = wide(2);
            purpose.setLayoutData(pg);
            for (ServiceCatalog.Endpoint ep : service.endpoints()) {
                String prefix = ep.path().split("\\{")[0];
                if (!ep.path().isEmpty() && !ep.path().equals("/") && api.path.startsWith(prefix.split("\\?")[0])) {
                    info(about, "This call", ep.summary() + (ep.contract() == null ? "" : " · " + ep.contract())).setForeground(Ui.SECONDARY);
                    break;
                }
            }
            info(about, "Use it for", String.join(", ", service.cases().stream().map(u -> u.title).toList())).setForeground(Ui.SECONDARY);
            button(about, "Layer 3 Services…", () -> editor.main.showServices(service.id()));
        }

        Group g = section("API Call");
        combo(g, "Subscription", subscriptionNames(space), subscriptionIndex(space, api.subscriptionID), true,
                i -> api.subscriptionID = i == 0 ? null : space.subscriptions.get(i - 1).id);
        if (sub != null && !sub.projectFolder.isEmpty()) {
            Button open = button(g, "Open Project in " + sub.ide.title, () -> {
                String error = ProjectOpener.open(sub.projectFolder, sub.ide);
                if (error != null) editor.main.status(error);
            });
            open.setToolTipText(ProjectOpener.expand(sub.projectFolder).toString());
        }
        if (sub != null) {
            info(g, "Base URL", sub.baseURL);
            caption(g, "API key");
            keyStatus(g, sub).setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));
        }
        button(g, "Manage API Subscriptions…", () -> editor.main.showSubscriptions(editor, api.subscriptionID));
        combo(g, "Method", titles(HttpMethod.values(), HttpMethod::raw), api.method.ordinal(), true, i -> api.method = HttpMethod.values()[i]);
        text(g, "Endpoint path", api.path, "/query", v -> api.path = v).setFont(Ui.mono());

        keyValues(section("Query Parameters"), api.query, "name", "value", "Add Parameter");
        keyValues(section("Headers"), api.headers, "header", "value", "Add Header");
        if (api.method == HttpMethod.POST) multi(section("Body (JSON)"), null, api.body, null, 6, true, v -> api.body = v);

        Group tryIt = section("Try It");
        Composite r = row(tryIt, 2);
        Button test = Ui.button(r, "⚡ Test Call", () -> runTest(node, false));
        test.setEnabled(sub != null && !testing.contains(node.id));
        Label summary = new Label(r, SWT.NONE);
        summary.setLayoutData(Ui.fillH());
        ApiTester.Result result = results.get(node.id);
        if (testing.contains(node.id)) summary.setText("Sending…");
        else if (result != null && result.error() == null) {
            summary.setText((result.status() == null ? 0 : result.status()) + " · " + Math.round(result.elapsed() * 1000) + " ms");
            summary.setForeground(result.passed() ? Ui.GREEN : Ui.ORANGE);
        }
        if (result != null) testResult(tryIt, result);
        footer(tryIt, "Sends one request now with the stored key and checks the expected outcome.");
    }

    private void scriptSection(JobNode node, Jobspace space) {
        ScriptSpec spec = node.script();
        String found = PythonRunner.locate(spec.interpreter);
        Group g = section("Python Script");
        Text interp = text(g, "Interpreter", spec.interpreter, PythonRunner.locate("") != null ? PythonRunner.locate("") : "python.exe path (none found)", v -> spec.interpreter = v);
        interp.setFont(Ui.mono());
        interp.addListener(SWT.FocusOut, e -> {
            PythonRunner.rescan();
            editor.revalidate();
            rebuildLater();
        });
        if (found == null) {
            Label warn = new Label(g, SWT.WRAP);
            warn.setText(spec.interpreter.isEmpty() ? "⚠ No Python found — install it from python.org (add it to the PATH) or enter its path."
                    : "⚠ Not found or not executable.");
            warn.setForeground(Ui.ORANGE);
            warn.setLayoutData(wide(2));
        }
        combo(g, "Subscription", subscriptionNames(space), subscriptionIndex(space, spec.subscriptionID), true,
                i -> spec.subscriptionID = i == 0 ? null : space.subscriptions.get(i - 1).id);
        ApiSubscription sub = space.subscription(spec.subscriptionID);
        if (sub != null) {
            caption(g, "API key");
            keyStatus(g, sub).setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));
        }
        footer(g, "The script reads {\"arguments\", \"inputs\", \"step\"} as JSON on stdin and prints its output as JSON. "
                + "The subscription's key and base URL arrive as MXJOBS_API_KEY and MXJOBS_BASE_URL.");

        Group code = section("Code");
        Text t = multi(code, null, spec.code, null, 14, true, v -> spec.code = v);
        t.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_TAB_NEXT && (e.stateMask & SWT.MOD1) == 0) {
                e.doit = false;
                e.detail = SWT.TRAVERSE_NONE;
                t.insert("    ");
            }
        });
        if (!spec.code.equals(ScriptSpec.TEMPLATE)) button(code, "Reset to Template", () -> edit(null, () -> spec.code = ScriptSpec.TEMPLATE, true));

        Group tryIt = section("Try It");
        Composite r = row(tryIt, 2);
        Button run = Ui.button(r, "▶ Run Script", () -> runTest(node, true));
        run.setEnabled(found != null && !testing.contains(node.id));
        Label summary = new Label(r, SWT.NONE);
        summary.setLayoutData(Ui.fillH());
        ApiTester.Result result = results.get(node.id);
        if (testing.contains(node.id)) summary.setText("Running…");
        else if (result != null && result.error() == null) {
            summary.setText("exit " + (result.status() == null ? -1 : result.status()) + " · " + Math.round(result.elapsed() * 1000) + " ms");
            summary.setForeground(result.passed() ? Ui.GREEN : Ui.ORANGE);
        }
        if (result != null) testResult(tryIt, result);
        footer(tryIt, "Runs the script once with the arguments above and the inputs from the last run, then checks the expected outcome.");
    }

    private void runTest(JobNode node, boolean script) {
        Jobspace space = editor.space;
        JobNode snapshot = node.copy();
        ApiSubscription sub = space.subscription(script ? node.script().subscriptionID : node.api().subscriptionID);
        ApiSubscription subCopy = sub == null ? null : ApiSubscription.read(sub.toJson());
        if (!script && subCopy == null) return;
        var inputs = RunEngine.lastInputs(space, editor.run, node.id);
        testing.add(node.id);
        results.remove(node.id);
        rebuild();
        Background.run(scroller, () -> script ? PythonRunner.run(snapshot, subCopy, inputs) : ApiTester.run(snapshot, subCopy, inputs), result -> {
            testing.remove(node.id);
            results.put(node.id, result);
            rebuild();
        }, error -> {
            testing.remove(node.id);
            results.put(node.id, new ApiTester.Result("", null, 0, "", List.of(), String.valueOf(error.getMessage()), null));
            rebuild();
        });
    }

    private void testResult(Composite g, ApiTester.Result result) {
        if (result.error() != null) {
            Text err = Ui.selectable(g, "✖ " + result.error(), SWT.NONE);
            err.setForeground(Ui.RED);
            err.setLayoutData(wide(2));
        }
        if (!result.url().isEmpty()) {
            Text url = Ui.selectable(g, result.url(), SWT.NONE);
            url.setFont(Ui.small());
            url.setForeground(Ui.SECONDARY);
            url.setLayoutData(wide(2));
        }
        for (ApiTester.Check c : result.checks()) {
            Label l = Ui.label(g, (c.passed() ? "✔ " : "✖ ") + c.label());
            l.setFont(Ui.small());
            l.setForeground(c.passed() ? Ui.GREEN : Ui.RED);
            l.setLayoutData(wide(2));
        }
        if (!result.body().isEmpty()) {
            Text body = new Text(g, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
            body.setText(result.body());
            body.setFont(Ui.mono());
            GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            gd.heightHint = 200;
            gd.widthHint = 120;
            body.setLayoutData(gd);
        }
    }

    private void outcomeSection(JobNode node, Jobspace space) {
        ExpectedOutcome outcome = node.outcome;
        Group g = section("Expected Outcome");
        multi(g, "Description", outcome.description, null, 2, false, v -> outcome.description = v);
        text(g, "Output name", outcome.outputName, "published to downstream nodes", v -> outcome.outputName = v);
        if (node.kind == NodeKind.API_READ) text(g, "Success HTTP status", outcome.successStatus, "200-299", v -> outcome.successStatus = v);
        caption(g, "Required fields").setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
        strings(g, outcome.requiredFields, "dotted.json.path", "Add Required Field", List.of());
        caption(g, "Value checks").setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
        for (ValueAssertion a : outcome.assertions) {
            Composite r = row(g, a.op == AssertionOp.EXISTS ? 3 : 4);
            rowText(r, a.path, "solverStatus", "as:" + a.id + ":p", v -> a.path = v).setFont(Ui.mono());
            Combo op = new Combo(r, SWT.READ_ONLY);
            op.setItems(titles(AssertionOp.values(), AssertionOp::raw));
            op.select(a.op.ordinal());
            op.addListener(SWT.Selection, e -> edit(null, () -> a.op = AssertionOp.values()[op.getSelectionIndex()], true));
            if (a.op != AssertionOp.EXISTS) rowText(r, a.value, "optimal", "as:" + a.id + ":v", v -> a.value = v).setFont(Ui.mono());
            remove(r, () -> outcome.assertions.remove(a));
        }
        button(g, "+ Add Value Check", () -> edit(null, () -> outcome.assertions.add(new ValueAssertion()), true));
        footer(g, "The step fails when the status, a required field or a value check fails; the exception handling below then applies. Values may use {{argument}}.");
    }

    private void exceptionSection(ExceptionPolicy policy) {
        Group g = section("Exception Handling");
        combo(g, "On failure", titles(FailureAction.values(), f -> f.title), policy.onFailure.ordinal(), true, i -> policy.onFailure = FailureAction.values()[i]);
        if (policy.onFailure == FailureAction.RETRY) {
            spinner(g, "Retries", policy.maxRetries, 0, 10, 1, v -> policy.maxRetries = v);
            spinner(g, "Delay (s)", policy.retryDelaySeconds, 0, 600, 1, v -> policy.retryDelaySeconds = v);
            check(g, "Exponential backoff", policy.exponentialBackoff, false, v -> policy.exponentialBackoff = v);
        }
        if (policy.onFailure == FailureAction.USE_FALLBACK) text(g, "Fallback value", policy.fallbackValue, null, v -> policy.fallbackValue = v);
        spinner(g, "Timeout (s)", policy.timeoutSeconds, 1, 7200, policy.timeoutSeconds >= 120 ? 60 : 5, v -> policy.timeoutSeconds = v);
        check(g, "Notify the user on failure", policy.notifyUser, false, v -> policy.notifyUser = v);
    }
}
