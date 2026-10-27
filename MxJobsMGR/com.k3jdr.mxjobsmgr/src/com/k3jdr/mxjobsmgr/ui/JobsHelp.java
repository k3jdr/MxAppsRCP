package com.k3jdr.mxjobsmgr.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxjobsmgr.engine.RunEngine;
import com.k3jdr.mxjobsmgr.engine.Validator;
import com.k3jdr.mxjobsmgr.model.JobLink;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.NodeKind;

/**
 * What Quick Help says in MxJobsMGR (JobsQuickHelp.swift): the jobspace window — canvas, palette, inspector, run bar
 * and console — the API subscriptions, and the Services, Samples and API key windows. Shortcuts as on Windows.
 */
public final class JobsHelp {
    private JobsHelp() {}

    // MARK: Screens

    public static QuickHelpItem screen(Jobspace space, int problems) {
        String counts = space.nodes.size() + " step" + (space.nodes.size() == 1 ? "" : "s") + ", " + space.links.size() + " link"
                + (space.links.size() == 1 ? "" : "s") + (problems == 0 ? ", no problems." : ", " + problems + " problem" + (problems == 1 ? "" : "s") + " (Ctrl+Shift+K).");
        return new QuickHelpItem(space.name.isEmpty() ? "Jobspace" : space.name,
                "A three-layer workflow: business operations in Layer 1 at the top, agents in Layer 2, data reads in Layer 3. "
                        + "Drag steps from the palette onto the canvas, link them with the ● handle, set each up in the inspector (Ctrl+Alt+I), "
                        + "and run it with ▶ (Ctrl+R) or a step at a time (F6).\n" + counts);
    }

    public static final QuickHelpItem NO_DOCUMENT = new QuickHelpItem("MxJobsMGR",
            "Design the day-to-day financial market data operation as a three-layer swim-lane diagram saved as JSON jobspace files. "
                    + "File ▸ New (Ctrl+N) starts an empty jobspace, New from Sample… (Ctrl+Alt+N) a complete workflow, Open… (Ctrl+O) a .jobspace file.");

    public static final QuickHelpItem SUBSCRIPTIONS = new QuickHelpItem("API Subscriptions",
            "The data services this jobspace's Layer 3 steps call: base URL, plan, rate limit, how the key is sent, and headers sent "
                    + "with every call. Each sends the service key of its MxAcctMGR secure account; keys are never in the jobspace file. + adds a subscription or the local Python "
                    + "service; Get a Key… and ? show how to get one, Secure account picks the MxAcctMGR account that holds it, Test Key tries it.",
            "Ctrl+Alt+K");

    /** What's selected on the canvas. */
    public static QuickHelpItem selection(Editor editor) {
        Jobspace space = editor.space;
        if (editor.selectedNodes.size() > 1) {
            return new QuickHelpItem(editor.selectedNodes.size() + " steps selected",
                    "Drag one to move them together; arrow keys nudge them (Shift for bigger steps). Ctrl+D duplicates, Delete removes.");
        }
        if (editor.selectedNodes.size() == 1) {
            JobNode node = space.node(editor.selectedNodes.iterator().next());
            if (node != null) return node(node, editor.run.state(node.id), null, true);
        }
        JobLink link = space.link(editor.selectedLink);
        if (link != null) {
            JobNode from = space.node(link.from), to = space.node(link.to);
            boolean up = to != null && from != null && to.layer().number < from.layer().number;
            return new QuickHelpItem("Link: " + (from != null ? from.title : "–") + " → " + (to != null ? to.title : "–"),
                    (up ? "Points up the layers (dashed): it carries results back. " : "Points down the layers: a call. ")
                            + "Name it in the inspector; Delete removes it.");
        }
        return null;
    }

    // MARK: Canvas

    private static final String CANVAS_DETAIL = "Click a step to select it, Shift-click to add to the selection, drag on empty canvas to select several. "
            + "Drop palette steps here. Ctrl+wheel or Ctrl+= Ctrl+- to zoom; right-click to run or save the diagram as PNG or draw.io SVG.";

    private static final String[] LANE_DETAIL = {
            "Steps here start the run, assemble instructions, wait for approval, execute and report. Steps stay in their own lane.",
            "Agents call the Layer 3 steps linked below them and hand their results up; Decision routes the run.",
            "API reads and Python steps call the data services with your subscriptions' keys; Normalize maps their fields." };

    public static QuickHelpItem canvas(Layer lane) {
        if (lane == null) return new QuickHelpItem("Canvas", CANVAS_DETAIL);
        return new QuickHelpItem("Layer " + lane.number + " · " + lane.title, lane.subtitle + ". " + LANE_DETAIL[lane.number - 1] + "\n" + CANVAS_DETAIL);
    }

    /** A step on the canvas: what kind it is, how it last ran, and what to do with it. */
    public static QuickHelpItem node(JobNode node, RunEngine.NodeState state, Validator.Severity issue, boolean selected) {
        List<String> facts = new ArrayList<>();
        facts.add("Layer " + node.layer().number);
        if (!node.enabled) facts.add("disabled");
        if (node.breakpoint) facts.add("breakpoint");
        if (state != null && state != RunEngine.NodeState.PENDING) facts.add("last run: " + state.title.toLowerCase());
        if (issue != null) facts.add(issue == Validator.Severity.ERROR ? "has an error (see Checks)" : "has a warning (see Checks)");
        String how = selected
                ? "The inspector on the right sets it up. Ctrl+\\ toggles a breakpoint, Ctrl+D duplicates, Delete removes it."
                : "Click to select it and set it up in the inspector; drag to move it; drag its ● handle onto another step to link them. Right-click for breakpoint, disable, Edit Step…, its link and hover text, duplicate, delete.";
        if (node.link != null) facts.add("has a link (rest the pointer for its hover text)");
        else if (PaletteEdits.shownLink(node) != null) facts.add("shows its palette element's link");
        String notes = node.notes.strip();
        return new QuickHelpItem(node.kind.title + ": " + node.title,
                (notes.isEmpty() ? "" : notes + "\n") + KIND_DETAIL.getOrDefault(node.kind, node.kind.summary) + "\n" + String.join(" · ", facts) + ". " + how);
    }

    public static final QuickHelpItem HANDLE = new QuickHelpItem("Link Handle",
            "Drag onto another step to link them: calls go down the layers, results come back up (dashed).");

    public static final QuickHelpItem BREAKPOINT = new QuickHelpItem("Breakpoint",
            "Runs pause before this step (when breakpoints are on, Ctrl+Y). Ctrl+\\ toggles it on the selected step.", "Ctrl+\\");

    // MARK: Palette

    public static final Map<NodeKind, String> KIND_DETAIL = Map.ofEntries(
            Map.entry(NodeKind.TRIGGER, "Starts a run: manually, daily or on weekdays at a set time, or every so many minutes (Schedule in the inspector). It goes first in Layer 1."),
            Map.entry(NodeKind.INSTRUCTION_SET, "Builds the instructions that go for approval — a trade list, say — from the results linked into it. Set the assembly instructions and output format in the inspector."),
            Map.entry(NodeKind.APPROVAL_GATE, "Pauses the run until the approver answers in the banner above the canvas; nothing downstream runs before then. Set the approver, how they're asked, the expiry and the summary they see."),
            Map.entry(NodeKind.EXECUTE_REQUEST, "Sends the approved instructions to their target: a dry run (nothing sent), a paper / sandbox account, or live. Confirm Again asks once more before sending."),
            Map.entry(NodeKind.OUTCOME_REPORT, "Reports what the run did to its recipients, optionally in detail and with failures and data gaps."),
            Map.entry(NodeKind.AGENT_TASK, "An agent that gathers data points by calling the Layer 3 steps linked below it, following its instructions (simulated: it passes its inputs on)."),
            Map.entry(NodeKind.AGGREGATE, "Combines the outputs of the steps linked into it into one result."),
            Map.entry(NodeKind.DECISION, "Routes the run on its inputs' decision context (the first of its Inputs that has one, else the most confident); with none, it escalates to you."),
            Map.entry(NodeKind.API_READ, "Calls one endpoint of an API subscription — method, path, query parameters, headers — sending the service key of the subscription's secure account (MxAcctMGR ▸ Secure Accounts). Test Call in the inspector tries it now."),
            Map.entry(NodeKind.PYTHON_SCRIPT, "Runs Python: arguments and linked inputs arrive as JSON on stdin and it prints JSON. Layer 3 service steps call the shared services (Services/). Run Script in the inspector tries it."),
            Map.entry(NodeKind.DATA_NORMALIZE, "Maps fields of a raw response to named data points: each output field takes the value at a dotted JSON path."));

    public static QuickHelpItem paletteKind(NodeKind kind) {
        boolean edited = PaletteEdits.isEdited(kind);
        String own = edited ? "Built in: " + kind.title + " — " + kind.summary + "\n" : "";
        return new QuickHelpItem(PaletteEdits.title(kind),
                (edited ? PaletteEdits.summary(kind) + "\n" + own : KIND_DETAIL.getOrDefault(kind, kind.summary) + "\n")
                        + "Drag it onto its lane on the canvas, or double-click to add it at the end of Layer " + kind.layer.number + ". "
                        + "Right-click to rename it (Edit Element…, or select it and press Enter) or to "
                        + (PaletteEdits.link(PaletteEdits.kindItem(kind)) == null ? "add a link with hover text." : "edit its link; rest the pointer on it for the link's hover text."));
    }

    public static QuickHelpItem paletteLayer(Layer layer) {
        return new QuickHelpItem("Layer " + layer.number + " · " + layer.title, layer.subtitle + ". Right-click the header to "
                + (PaletteEdits.link(PaletteEdits.layerItem(layer)) == null ? "add a link with hover text." : "edit its link; rest the pointer on it for the link's hover text."));
    }

    public static QuickHelpItem paletteService(ServiceCatalog.Service service, boolean running) {
        return new QuickHelpItem(service.name() + " (" + service.language() + ")", service.purpose() + "\n"
                + (running ? "Running on this PC. " : "Not running on this PC. ") + "Double-click to add a Layer 3 step calling it; right-click for each endpoint.");
    }

    public static final QuickHelpItem PALETTE = new QuickHelpItem("Palette",
            "The steps you can add, by layer, and the shared Layer 3 services. Drag a step onto the canvas or double-click it; right-click a step to rename it or link it, a layer's header to link it, and a service for its endpoints.");

    // MARK: Inspector

    public static QuickHelpItem inspector(Editor editor) {
        if (editor.selectedNodes.size() == 1) {
            JobNode node = editor.space.node(editor.selectedNodes.iterator().next());
            if (node != null) return new QuickHelpItem("Inspector: " + node.kind.title,
                    "The selected step's settings: title, enabled, breakpoint and notes; what a " + node.kind.title + " does; its arguments ({{name}} in paths and templates); "
                            + "the expected outcome that decides success; and what happens when it fails.", "Ctrl+Alt+I");
        }
        if (editor.selectedLink != null)
            return new QuickHelpItem("Inspector: Link", "Where the link runs from and to, and its label on the canvas.", "Ctrl+Alt+I");
        return new QuickHelpItem("Inspector: Jobspace",
                "With nothing selected: the jobspace's name and description, steps per layer, its API subscriptions with their key status, and the configuration checks. Click a check to go to its step.",
                "Ctrl+Alt+I");
    }

    public static QuickHelpItem section(String name) {
        String detail = SECTION_DETAIL.get(name);
        return detail == null ? null : new QuickHelpItem(name, detail);
    }

    private static final Map<String, String> SECTION_DETAIL = Map.ofEntries(
            Map.entry("Schedule", "When the trigger starts a run: manually, daily or on weekdays at a time in a time zone, or every N minutes."),
            Map.entry("Agent", "What the agent is told to do, the model it would use, the output format, and the inputs (linked steps' outputs) it reads."),
            Map.entry("Instruction Assembly", "How the instructions are put together from the linked results, and their output format."),
            Map.entry("Decision", "What the decision weighs. Its Inputs list sets which linked result's decision context comes first."),
            Map.entry("User Approval", "Who approves, how they're asked (in app, email, message), when the request expires, whether a comment is required, and the summary they see."),
            Map.entry("Execution Request", "Where approved instructions go and how: dry run, paper / sandbox, or live (real requests once approved)."),
            Map.entry("Outcome Report", "Who gets the report, whether it's detailed, and whether failures and data gaps are included."),
            Map.entry("API Call", "The subscription (base URL and key), method and endpoint path. Use {{argument}} placeholders in the path."),
            Map.entry("Query Parameters", "Name / value pairs added to the URL; values may use {{argument}} and {{today}}."),
            Map.entry("Headers", "Headers sent with this call, on top of the subscription's own."),
            Map.entry("Body (JSON)", "The JSON sent with a POST. Use {{argument}}, {{in:name.path}} for a value of a linked step's output and {{json:name.path}} for it as JSON."),
            Map.entry("Try It", "Sends one request (or runs the script) now with the stored key and the inputs from the last run, and checks the expected outcome."),
            Map.entry("Python Script", "The python that runs the script and the subscription whose key and base URL it gets (MXJOBS_API_KEY, MXJOBS_BASE_URL)."),
            Map.entry("Code", "The script: it reads {\"arguments\", \"inputs\", \"step\"} as JSON on stdin and prints its result as JSON."),
            Map.entry("Field Mappings", "Each output field takes the value at a dotted JSON path of the incoming data."),
            Map.entry("Arguments", "Named values for this step: use {{name}} in paths, parameters, headers and templates. Built-ins: {{today}}, {{yesterday}}."),
            Map.entry("Expected Outcome", "What success means: HTTP status, required fields, and value checks (path, comparison, value). The output name publishes the result to the steps downstream."),
            Map.entry("Exception Handling", "What a failure does: retry then fail, skip and continue, use a fallback value, ask you, or abort the run."),
            Map.entry("Checks", "Configuration problems with this step or the jobspace. Click one to go to it."),
            Map.entry("Last Run", "How the step did in the last run and its last log message; Show Output opens it in the console."),
            Map.entry("About This Service", "The shared Layer 3 service behind this step (Services/): what it's for, its endpoints, and the Layer 3 Services window."),
            Map.entry("Step", "The step's title, whether it runs (Enabled), a breakpoint that pauses runs before it, and your notes."),
            Map.entry("Jobspace", "The jobspace's name and description, saved in the file."),
            Map.entry("Layers", "How many steps each layer has, and the links between them."),
            Map.entry("Layer 3 API Subscriptions", "The jobspace's subscriptions and whether each has its key in MxAcctMGR ▸ Secure Accounts. Manage API Subscriptions… edits them."),
            Map.entry("Link", "Where the link runs from and to, and its label on the canvas."),
            // API Subscriptions
            Map.entry("Service", "The subscription's name, provider, base URL, plan, rate limit and documentation link."),
            Map.entry("API Key", "How the key is sent (query parameter, header, bearer token, local service, none) and the MxAcctMGR secure account whose service key is sent. Keys are entered and changed in MxAcctMGR ▸ Banking ▸ Secure Accounts: Open in MxAcctMGR shows the account, New in MxAcctMGR… makes one. Test Key tries it."),
            Map.entry("Headers Sent With Every Call", "Headers every step using this subscription sends."),
            Map.entry("Source Project", "For services you develop (Services/portfolio-java, portfolio-cpp): the project folder and the IDE its steps' Open Project button opens."),
            Map.entry("Notes", "Anything worth remembering about the subscription."));

    // MARK: Run

    public static final QuickHelpItem RUN_BAR = new QuickHelpItem("Run Bar",
            "Run, pause, step and stop the jobspace; choose what each layer does in a run (L1 interactive or auto-approve, L2 run or skip, L3 live, mock or skip); breakpoints; options; the run's status.");

    public static QuickHelpItem layerAction(Layer layer) {
        String detail = switch (layer) {
            case OPERATIONS -> "Interactive asks you at each approval; Auto-approve rehearses the run without asking; Skip leaves Layer 1 out.";
            case AGENTS -> "Run the agents (simulated: they pass their inputs on), or skip Layer 2.";
            case DATA -> "Live calls the APIs with your keys; Mock makes up responses that pass the checks, with no network; Skip leaves Layer 3 out.";
        };
        return new QuickHelpItem("Layer " + layer.number + " in a Run", detail);
    }

    public static final QuickHelpItem CONSOLE = new QuickHelpItem("Run Console",
            "The run log on the left (filter by level; click an entry to select its step) and the selected step's output as JSON on the right. Drag the line above it to resize.",
            "Ctrl+Shift+Y");

    public static QuickHelpItem prompt(RunEngine.Prompt prompt) {
        return new QuickHelpItem("Waiting for You: " + prompt.title(), "The run is paused on this step until you choose. "
                + String.join(" · ", prompt.options().stream().map(RunEngine.Option::label).toList())
                + (prompt.detail().isEmpty() ? "" : ". Details shows what you're approving."));
    }

    // MARK: Other windows

    public static final QuickHelpItem SERVICES = new QuickHelpItem("Layer 3 Services",
            "The shared services (Services/) the workflows call: what each is for, its language, endpoints and how to start it. Add Step puts a step calling an endpoint into the front jobspace; a sample opens a complete workflow built on it.",
            "Ctrl+Alt+L");

    public static final QuickHelpItem SAMPLES = new QuickHelpItem("New from Sample",
            "Complete three-layer workflows to start from: data refresh, active optimization, passive tracking, attribution. Create opens one as a new jobspace.",
            "Ctrl+Alt+N");

    public static final QuickHelpItem KEYS = new QuickHelpItem("Adding API Keys",
            "How to get a key for each data provider, put it in MxAcctMGR ▸ Secure Accounts, and choose that secure account for a subscription. Keys are never in the jobspace file.");
}
