package com.k3jdr.mxjobsmgr.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.k3jdr.mx.core.json.AppleJson;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobLink;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.Argument;
import com.k3jdr.mxjobsmgr.model.Specs.DataAction;
import com.k3jdr.mxjobsmgr.model.Specs.ExceptionPolicy;
import com.k3jdr.mxjobsmgr.model.Specs.ExecutionSpec;
import com.k3jdr.mxjobsmgr.model.Specs.FailureAction;
import com.k3jdr.mxjobsmgr.model.Specs.KeyValue;
import com.k3jdr.mxjobsmgr.model.Specs.OperationsAction;
import com.k3jdr.mxjobsmgr.model.Specs.RunSettings;
import com.k3jdr.mxjobsmgr.model.Specs.ScriptSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ValueAssertion;

/**
 * Runs (and debugs) a jobspace across its three layers (RunEngine.swift), on a worker thread.
 *
 * <p>Order: a step first waits for the steps feeding it along its own layer or from below (same-layer flow, results
 * coming up). It then performs its action and runs the steps it calls down the layers, together with whatever those
 * call in turn. Layer 2 agents act after their calls have returned, so they see the data the Layer 3 reads brought
 * back. Each step runs once; cycles (a call down whose result comes back up) are fine.
 *
 * <p>What each layer does is set in the jobspace's run settings (Interactive / Auto-approve / Skip, Run / Skip,
 * Live / Mock / Skip). Execution Requests never send anything: they report what would be sent. Breakpoints and Step
 * pause before a step; Continue resumes; Stop cancels. User Approval, send confirmations and "ask the user" failures
 * wait on a prompt.
 *
 * <p>The UI reads the state (phase, states, log, outputs, prompt) and is told of changes through the listener, called
 * on the worker thread.
 */
public final class RunEngine {

    public enum NodeState {
        PENDING("Pending", "◌", 0x8E8E93), PAUSED("Paused", "⏸", 0x9B59B6), RUNNING("Running", "▶", 0x2F6FDB),
        WAITING("Waiting for you", "?", 0xE08600), SUCCEEDED("Succeeded", "✔", 0x2E9E48), WARNING("Completed with warnings", "!", 0xE08600),
        FAILED("Failed", "✖", 0xD03030), SKIPPED("Skipped", "↷", 0x8E8E93);

        public final String title, symbol;
        public final int rgb;

        NodeState(String title, String symbol, int rgb) {
            this.title = title;
            this.symbol = symbol;
            this.rgb = rgb;
        }
    }

    public enum Phase {
        IDLE, RUNNING, PAUSED, WAITING, FINISHED, FAILED, STOPPED;

        public boolean isActive() { return this == RUNNING || this == PAUSED || this == WAITING; }
    }

    public enum Level {
        DEBUG("•", 0x8E8E93), INFO("ⓘ", 0x2F6FDB), SUCCESS("✔", 0x2E9E48), WARNING("⚠", 0xE08600), ERROR("✖", 0xD03030);

        public final String symbol;
        public final int rgb;

        Level(String symbol, int rgb) {
            this.symbol = symbol;
            this.rgb = rgb;
        }
    }

    public record LogEntry(Instant date, Level level, String nodeID, String message) {}

    public record Option(String label, boolean isDefault, boolean isDestructive) {}

    /** A question the run is waiting on (approval, confirmation, failure handling, Python consent). */
    public record Prompt(String nodeID, String title, String message, String detail, List<Option> options) {}

    /** What the run reads from the open document at each checkpoint, so breakpoints toggled mid-run count. */
    public record Live(Set<String> breakpoints, boolean breakpointsEnabled, double stepDelay) {}

    private static final class Cancelled extends RuntimeException {
        Cancelled() { super(null, null, false, false); }
    }

    private static final class Abort extends RuntimeException {
        Abort() { super(null, null, false, false); }
    }

    // State the UI reads
    private volatile Phase phase = Phase.IDLE;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();
    private final List<LogEntry> log = Collections.synchronizedList(new ArrayList<>());
    private volatile Prompt prompt;
    private volatile String currentNode;
    private volatile Instant startedAt, finishedAt;
    private final Map<String, JsonElement> outputs = new ConcurrentHashMap<>();

    /** Pause before the next step (Step / Pause). */
    private volatile boolean stepping;
    private volatile Live live = new Live(Set.of(), true, 0.4);
    private volatile Runnable listener = () -> {};

    private Thread worker;
    private volatile boolean cancelled;
    private volatile CompletableFuture<Void> resume;
    private volatile CompletableFuture<String> answer;

    // Per-run bookkeeping (worker thread)
    private Jobspace space = new Jobspace();
    private RunSettings settings = new RunSettings();
    private final Set<String> done = new HashSet<>();
    private final Set<String> active = new HashSet<>();
    /** Steps whose failure or rejection stops what depends on them. */
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();
    /** Whether the user allowed this run's Python scripts (asked once, at the first one). */
    private Boolean scriptsAllowed;

    public void setListener(Runnable listener) { this.listener = listener == null ? () -> {} : listener; }
    public void setLive(Live live) { this.live = live; }

    public Phase phase() { return phase; }
    public boolean isActive() { return phase.isActive(); }
    public boolean canContinue() { return phase == Phase.PAUSED; }
    public NodeState state(String id) { return states.get(id); }
    public Map<String, NodeState> states() { return states; }
    public Prompt prompt() { return prompt; }
    public String currentNode() { return currentNode; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public JsonElement output(String id) { return outputs.get(id); }

    public List<LogEntry> log() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    private void changed() {
        listener.run();
    }

    // MARK: Controls (UI thread)

    /** Starts a run of {@code space} (a copy is taken), or continues a paused one. */
    public synchronized void run(Jobspace space, boolean stepping) {
        if (phase == Phase.PAUSED) {
            this.stepping = stepping;
            continueRun();
            return;
        }
        if (isActive()) return;
        this.space = space.copy();
        settings = this.space.run;
        this.stepping = stepping;
        done.clear();
        active.clear();
        blocked.clear();
        scriptsAllowed = null;
        outputs.clear();
        states.clear();
        for (JobNode n : this.space.nodes) states.put(n.id, NodeState.PENDING);
        log.clear();
        prompt = null;
        currentNode = null;
        startedAt = Instant.now();
        finishedAt = null;
        cancelled = false;
        phase = Phase.RUNNING;
        RunSettings s = settings;
        write(Level.INFO, "Run started — Layer 1: " + s.operations.shortTitle + ", Layer 2: " + s.agents.shortTitle + ", Layer 3: "
                + s.data.shortTitle + (s.breakpointsEnabled ? "" : ", breakpoints off"), null);
        worker = new Thread(this::main, "MxJobsMGR run");
        worker.setDaemon(true);
        worker.start();
    }

    public void continueRun() {
        if (phase != Phase.PAUSED) return;
        phase = Phase.RUNNING;
        CompletableFuture<Void> r = resume;
        resume = null;
        if (r != null) r.complete(null);
        changed();
    }

    /** Runs to the next step and pauses before it (starts a run when idle). */
    public void step(Jobspace space) {
        if (phase == Phase.PAUSED) {
            stepping = true;
            continueRun();
        } else if (!isActive()) {
            run(space, true);
        }
    }

    /** Pauses before the next step. */
    public void pause() {
        if (phase != Phase.RUNNING) return;
        stepping = true;
        write(Level.DEBUG, "Pausing before the next step…", null);
    }

    public void stop() {
        if (!isActive()) return;
        cancelled = true;
        Thread w = worker;
        if (w != null) w.interrupt();
        CompletableFuture<Void> r = resume;
        if (r != null) r.complete(null);
        CompletableFuture<String> a = answer;
        if (a != null) a.complete("Stop");
    }

    /** Answers the prompt the run waits on. */
    public void respond(String label) {
        CompletableFuture<String> a = answer;
        if (a == null) return;
        // Clear first so a second click cannot answer the next prompt.
        answer = null;
        prompt = null;
        a.complete(label);
        changed();
    }

    public void reset() {
        if (isActive()) return;
        phase = Phase.IDLE;
        states.clear();
        log.clear();
        outputs.clear();
        currentNode = null;
        startedAt = null;
        finishedAt = null;
        changed();
    }

    public void clearLog() {
        log.clear();
        changed();
    }

    // MARK: Main loop

    private void main() {
        try {
            // Layer 1 first, left to right, then anything not reached from it.
            List<JobNode> order = new ArrayList<>(space.nodes);
            order.sort(Comparator.comparingInt((JobNode n) -> n.layer().number).thenComparingDouble(n -> n.x).thenComparingDouble(n -> n.y));
            for (JobNode node : order) runNode(node.id);
            long failed = states.values().stream().filter(s -> s == NodeState.FAILED).count();
            phase = failed > 0 ? Phase.FAILED : Phase.FINISHED;
            write(failed > 0 ? Level.ERROR : Level.SUCCESS,
                    failed > 0 ? "Run finished with " + failed + " failed step" + (failed == 1 ? "" : "s") + "." : "Run finished.", null);
        } catch (Cancelled e) {
            phase = Phase.STOPPED;
            markUnfinished();
            write(Level.WARNING, "Run stopped.", null);
        } catch (Abort e) {
            phase = Phase.FAILED;
            markUnfinished();
            write(Level.ERROR, "Run aborted.", null);
        } catch (RuntimeException e) {
            phase = Phase.FAILED;
            markUnfinished();
            write(Level.ERROR, "Run failed: " + e, null);
        }
        currentNode = null;
        prompt = null;
        finishedAt = Instant.now();
        worker = null;
        changed();
    }

    private void markUnfinished() {
        states.replaceAll((id, s) -> s == NodeState.RUNNING || s == NodeState.PAUSED || s == NodeState.WAITING ? NodeState.FAILED : s);
    }

    private void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new Cancelled();
    }

    private void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancelled();
        }
        checkCancelled();
    }

    // MARK: Graph

    /** Steps feeding {@code id} that it must wait for: along its own layer or from below, leaving out its own calls' results. */
    private List<String> dependencies(String id) {
        JobNode node = space.node(id);
        if (node == null) return List.of();
        List<String> own = calledSubtree(id);
        List<String> out = new ArrayList<>();
        for (JobLink link : space.links) {
            if (!link.to.equals(id)) continue;
            JobNode from = space.node(link.from);
            if (from == null || from.layer().number < node.layer().number || own.contains(from.id)) continue;
            out.add(from.id);
        }
        return out;
    }

    /** Steps {@code id} calls down the layers, and what those lead to in their own or lower layers. */
    private List<String> calledSubtree(String id) {
        JobNode root = space.node(id);
        if (root == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<JobNode> queue = new ArrayList<>();
        for (JobLink link : space.links) {
            if (!link.from.equals(id)) continue;
            JobNode to = space.node(link.to);
            if (to != null && to.layer().number > root.layer().number) queue.add(to);
        }
        queue.sort(Comparator.comparingDouble(n -> n.x));
        while (!queue.isEmpty()) {
            JobNode node = queue.remove(0);
            if (node.id.equals(id) || !seen.add(node.id)) continue;
            List<JobNode> next = new ArrayList<>();
            for (JobLink link : space.links) {
                if (!link.from.equals(node.id)) continue;
                JobNode to = space.node(link.to);
                if (to != null && to.layer().number >= node.layer().number) next.add(to);
            }
            next.sort(Comparator.comparingDouble(n -> n.x));
            queue.addAll(next);
        }
        return new ArrayList<>(seen);
    }

    /** Outputs of the steps linked into {@code id}, keyed by their output name (or title). */
    private JsonObject inputs(String id) {
        JsonObject result = new JsonObject();
        for (JobLink link : space.links) {
            if (!link.to.equals(id)) continue;
            JobNode from = space.node(link.from);
            JsonElement value = from == null ? null : outputs.get(from.id);
            if (value == null) continue;
            result.add(from.outcome.outputName.isEmpty() ? from.title : from.outcome.outputName, value);
        }
        return result;
    }

    /** The outputs of the steps linked into {@code id} from the last run (Test Call and Run Script use them). */
    public static JsonObject lastInputs(Jobspace space, RunEngine engine, String id) {
        JsonObject result = new JsonObject();
        for (JobLink link : space.links) {
            if (!link.to.equals(id)) continue;
            JobNode from = space.node(link.from);
            JsonElement value = from == null ? null : engine.output(from.id);
            if (value == null) continue;
            result.add(from.outcome.outputName.isEmpty() ? from.title : from.outcome.outputName, value);
        }
        return result;
    }

    // MARK: Steps

    private void runNode(String id) {
        JobNode node = space.node(id);
        if (done.contains(id) || active.contains(id) || node == null) return;
        active.add(id);
        try {
            for (String dep : dependencies(id)) runNode(dep);

            String reason = skipReason(node);
            if (reason != null) {
                states.put(id, NodeState.SKIPPED);
                write(Level.DEBUG, "Skipped — " + reason + ".", id);
                if (reason.startsWith("upstream")) {
                    blocked.add(id);
                } else {
                    // A step skipped by the run settings passes its inputs straight through.
                    outputs.put(id, inputs(id));
                }
                done.add(id);
                changed();
                return;
            }

            checkpoint(node);
            currentNode = id;
            states.put(id, NodeState.RUNNING);
            write(Level.INFO, "▶ " + node.kind.title + ": " + node.title, id);

            boolean actsAfterCalls = node.layer() == Layer.AGENTS;
            if (!actsAfterCalls) perform(node);
            if (!blocked.contains(id)) {
                for (String callee : calledSubtree(id)) runNode(callee);
            }
            if (actsAfterCalls) perform(node);
            done.add(id);
        } finally {
            active.remove(id);
        }
    }

    private String skipReason(JobNode node) {
        if (!node.enabled) return "step disabled";
        switch (node.layer()) {
            case OPERATIONS -> { if (settings.operations == OperationsAction.SKIP) return "Layer 1 set to Skip"; }
            case AGENTS -> { if (settings.agents == com.k3jdr.mxjobsmgr.model.Specs.AgentsAction.SKIP) return "Layer 2 set to Skip"; }
            case DATA -> { if (settings.data == DataAction.SKIP) return "Layer 3 set to Skip"; }
        }
        for (JobLink link : space.links) {
            if (link.to.equals(node.id) && blocked.contains(link.from)) {
                JobNode n = space.node(link.from);
                if (n != null) return "upstream step “" + n.title + "” did not complete";
            }
        }
        return null;
    }

    private void checkpoint(JobNode node) {
        checkCancelled();
        Live l = live;
        boolean hasBreakpoint = l.breakpoints().contains(node.id) && l.breakpointsEnabled();
        if (stepping || hasBreakpoint) {
            states.put(node.id, NodeState.PAUSED);
            currentNode = node.id;
            CompletableFuture<Void> r = new CompletableFuture<>();
            resume = r;
            phase = Phase.PAUSED;
            write(Level.DEBUG, hasBreakpoint ? "Breakpoint — paused before “" + node.title + "”." : "Paused before “" + node.title + "”.", node.id);
            stepping = false;
            if (cancelled) r.complete(null);
            await(r);
            checkCancelled();
            phase = Phase.RUNNING;
        }
        double delay = live.stepDelay();
        if (delay > 0) sleep(delay);
    }

    private static <T> T await(CompletableFuture<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } catch (ExecutionException e) {
            throw new Cancelled();
        }
    }

    private String ask(JobNode node, String title, String message, JsonElement detail, List<Option> options) {
        states.put(node.id, NodeState.WAITING);
        phase = Phase.WAITING;
        CompletableFuture<String> a = new CompletableFuture<>();
        answer = a;
        prompt = new Prompt(node.id, title, message, detail == null ? "" : Values.pretty(detail), options);
        write(Level.INFO, "Waiting: " + title, node.id);
        if (cancelled) a.complete("Stop");
        String reply = await(a);
        prompt = null;
        checkCancelled();
        phase = Phase.RUNNING;
        states.put(node.id, NodeState.RUNNING);
        changed();
        return reply;
    }

    private void finish(JobNode node, NodeState state, JsonElement output, String message, Level level) {
        states.put(node.id, state);
        if (output != null) outputs.put(node.id, output);
        Level lvl = level != null ? level
                : state == NodeState.FAILED ? Level.ERROR : state == NodeState.WARNING ? Level.WARNING : state == NodeState.SKIPPED ? Level.DEBUG : Level.SUCCESS;
        write(lvl, message, node.id);
    }

    private void finish(JobNode node, NodeState state, JsonElement output, String message) {
        finish(node, state, output, message, null);
    }

    /** A failure the step's exception handling could not recover from. */
    private void fail(JobNode node, String message) {
        blocked.add(node.id);
        finish(node, NodeState.FAILED, null, message);
        if (node.exceptions.notifyUser) write(Level.WARNING, "Notify: “" + node.title + "” failed.", node.id);
        if (settings.stopOnError) throw new Abort();
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private void perform(JobNode node) {
        JsonObject input = inputs(node.id);
        switch (node.kind) {
            case TRIGGER -> {
                JsonObject out = new JsonObject();
                out.addProperty("startedAt", (startedAt == null ? Instant.now() : startedAt).truncatedTo(ChronoUnit.SECONDS).toString());
                for (Argument arg : node.arguments) if (!arg.name.isEmpty()) out.addProperty(arg.name, arg.value);
                finish(node, NodeState.SUCCEEDED, out, "Refresh started (" + (node.schedule != null ? node.schedule.summary() : "manual") + ").");
            }
            case INSTRUCTION_SET -> {
                JsonObject out = new JsonObject();
                out.add("instructions", input);
                out.addProperty("assembly", node.agent != null ? node.agent.instructions : "");
                finish(node, NodeState.SUCCEEDED, out, "Instructions assembled from " + plural(input.size(), "input") + ".");
            }
            case APPROVAL_GATE -> {
                if (settings.operations == OperationsAction.AUTO_APPROVE) {
                    finish(node, NodeState.WARNING, AppleJson.object("approved", true, "by", "auto-approve"), "Auto-approved (rehearsal).");
                    return;
                }
                String approver = node.approval != null ? node.approval.approver : "";
                String summary = fill(ApiTester.substitute(node.approval != null ? node.approval.summaryTemplate : "", node.arguments), input);
                String reply = ask(node, "Approve “" + node.title + "”?",
                        (summary.isEmpty() ? "Review the instructions below." : summary) + (approver.isEmpty() ? "" : "\nApprover: " + approver),
                        input, List.of(new Option("Approve", true, false), new Option("Reject", false, true)));
                if (reply.equals("Approve")) {
                    finish(node, NodeState.SUCCEEDED, AppleJson.object("approved", true, "instructions", input), "Approved.");
                } else {
                    blocked.add(node.id);
                    finish(node, NodeState.WARNING, AppleJson.object("approved", false), "Rejected — the steps after it will not run.");
                }
            }
            case EXECUTE_REQUEST -> {
                ExecutionSpec spec = node.execution != null ? node.execution : new ExecutionSpec();
                if (spec.confirmBeforeSend && settings.operations == OperationsAction.INTERACTIVE) {
                    String reply = ask(node, "Send “" + node.title + "”?",
                            "Target: " + (spec.target.isEmpty() ? "not set" : spec.target) + " · Mode: " + spec.mode.title,
                            input, List.of(new Option("Send", true, false), new Option("Cancel", false, true)));
                    if (!reply.equals("Send")) {
                        blocked.add(node.id);
                        finish(node, NodeState.SKIPPED, AppleJson.object("sent", false), "Cancelled — nothing was sent.", Level.WARNING);
                        return;
                    }
                }
                JsonObject out = AppleJson.object("sent", false, "mode", spec.mode.raw(), "target", spec.target, "request", input);
                finish(node, NodeState.WARNING, out, "Not sent: execution connectors are not available yet. Would send to "
                        + (spec.target.isEmpty() ? "(no target)" : spec.target) + " in " + spec.mode.raw() + " mode.");
            }
            case OUTCOME_REPORT -> {
                Map<NodeState, Integer> counts = new EnumMap<>(NodeState.class);
                for (NodeState s : states.values()) counts.merge(s, 1, Integer::sum);
                String recipients = node.report != null ? node.report.recipients : "";
                JsonObject report = AppleJson.object("succeeded", counts.getOrDefault(NodeState.SUCCEEDED, 0),
                        "warnings", counts.getOrDefault(NodeState.WARNING, 0), "failed", counts.getOrDefault(NodeState.FAILED, 0),
                        "skipped", counts.getOrDefault(NodeState.SKIPPED, 0), "recipients", recipients, "results", input);
                finish(node, NodeState.SUCCEEDED, report, "Report for " + (recipients.isEmpty() ? "the user" : recipients) + ": "
                        + report.get("succeeded") + " succeeded, " + report.get("warnings") + " with warnings, " + report.get("failed")
                        + " failed, " + report.get("skipped") + " skipped.");
            }
            case DECISION -> decide(node, input);
            case AGENT_TASK, AGGREGATE -> {
                String model = node.agent != null ? node.agent.model : "";
                finish(node, NodeState.SUCCEEDED, input, "Agent simulated" + (model.isEmpty() ? "" : " (" + model + " not connected yet)")
                        + " — passed on " + plural(input.size(), "input") + ".");
            }
            case DATA_NORMALIZE -> {
                JsonElement source = input.size() == 1 ? input.entrySet().iterator().next().getValue() : input;
                JsonObject out = new JsonObject();
                List<String> missing = new ArrayList<>();
                if (node.transform != null) {
                    for (KeyValue m : node.transform.mappings) {
                        if (m.key.isEmpty()) continue;
                        JsonElement v = Values.lookup(m.value, source);
                        if (v != null) out.add(m.key, v);
                        else missing.add(m.key);
                    }
                }
                if (missing.isEmpty()) finish(node, NodeState.SUCCEEDED, out, "Normalized " + plural(out.size(), "field") + ".");
                else handleFailure(node, "Fields not found: " + String.join(", ", missing), out);
            }
            case API_READ -> performApiRead(node);
            case PYTHON_SCRIPT -> performScript(node);
        }
    }

    /**
     * Simulated decision: follow the decisionContext of a Layer 3 outcome — the first of the step's own Inputs list
     * that has one (its priority), else the most confident.
     */
    private void decide(JobNode node, JsonObject input) {
        List<String> priority = node.agent != null ? node.agent.inputs : List.of();
        record Ctx(String name, JsonObject ctx) {}
        List<Ctx> contexts = new ArrayList<>();
        for (var e : input.entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonObject() && v.getAsJsonObject().get("decisionContext") instanceof JsonObject ctx) contexts.add(new Ctx(e.getKey(), ctx));
        }
        java.util.function.ToIntFunction<String> rank = name -> {
            int i = priority.indexOf(name);
            return i < 0 ? priority.size() : i;
        };
        contexts.sort(Comparator.comparingInt((Ctx c) -> rank.applyAsInt(c.name()))
                .thenComparing(Comparator.comparingDouble((Ctx c) -> Values.asDouble(c.ctx().get("confidence"), 0)).reversed()));
        if (!contexts.isEmpty() && Values.isString(contexts.get(0).ctx().get("recommendation"))) {
            Ctx best = contexts.get(0);
            String next = best.ctx().get("recommendation").getAsString();
            double confidence = Values.asDouble(best.ctx().get("confidence"), 0);
            JsonObject out = new JsonObject();
            out.addProperty("nextStep", next);
            out.addProperty("confidence", confidence);
            out.addProperty("source", best.name());
            out.add("alternatives", best.ctx().has("alternatives") ? best.ctx().get("alternatives") : new JsonArray());
            out.add("reason", best.ctx().has("reason") ? best.ctx().get("reason") : new JsonPrimitive(""));
            out.add("inputs", input);
            finish(node, NodeState.SUCCEEDED, out, "Decision: " + next + " (confidence " + String.format(java.util.Locale.ROOT, "%.2f", confidence)
                    + ", from “" + best.name() + "”).");
        } else {
            // No context to decide on (e.g. the optimizer was skipped): hand it to the user.
            JsonObject out = AppleJson.object("nextStep", "escalate", "confidence", 0.0, "source", "",
                    "reason", "No decisionContext among the inputs.", "inputs", input);
            finish(node, NodeState.WARNING, out, "Decision: escalate — no decisionContext among " + plural(input.size(), "input") + ".");
        }
    }

    // MARK: API reads, scripts and exception handling

    private void performApiRead(JobNode node) {
        ApiSubscription sub = space.subscription(node.api != null ? node.api.subscriptionID : null);
        if (sub == null) {
            fail(node, "No API subscription set.");
            return;
        }
        if (settings.data == DataAction.MOCK) {
            finish(node, NodeState.SUCCEEDED, mockOutput(node), "Mock data for " + sub.name + " "
                    + ApiTester.substitute(node.api != null ? node.api.path : "", node.arguments) + " (no network call).");
            return;
        }
        JsonObject input = inputs(node.id);
        attempt(node, () -> {
            ApiTester.Result r = ApiTester.run(node, sub, input);
            return new Attempt(r, "HTTP " + (r.status() == null ? 0 : r.status()) + " in " + Math.round(r.elapsed() * 1000) + " ms — " + r.url());
        });
    }

    private void performScript(JobNode node) {
        ScriptSpec spec = node.script != null ? node.script : new ScriptSpec();
        ApiSubscription sub = space.subscription(spec.subscriptionID);
        if (settings.data == DataAction.MOCK) {
            finish(node, NodeState.SUCCEEDED, mockOutput(node), "Mock data instead of running the Python script.");
            return;
        }
        // A jobspace can carry code: ask once per run before any of its scripts runs.
        if (scriptsAllowed == null) {
            long count = space.nodes.stream().filter(n -> n.kind == NodeKind.PYTHON_SCRIPT && n.enabled).count();
            String reply = ask(node, "Run Python scripts?",
                    "This jobspace runs " + plural((int) count, "Python script") + " on this PC with your user's rights "
                            + "(files, network and installed packages). Review the code below before allowing.",
                    new JsonPrimitive(spec.code),
                    List.of(new Option("Run Scripts", true, false), new Option("Skip Scripts", false, false), new Option("Stop", false, true)));
            switch (reply) {
                case "Run Scripts" -> scriptsAllowed = true;
                case "Skip Scripts" -> scriptsAllowed = false;
                default -> throw new Cancelled();
            }
        }
        if (!scriptsAllowed) {
            finish(node, NodeState.SKIPPED, inputs(node.id), "Skipped — Python scripts not allowed in this run.", Level.WARNING);
            return;
        }
        JsonObject input = inputs(node.id);
        attempt(node, () -> {
            ApiTester.Result r = PythonRunner.run(node, sub, input);
            return new Attempt(r, "Python exited " + (r.status() == null ? -1 : r.status()) + " in " + Math.round(r.elapsed() * 1000) + " ms — " + r.url());
        });
    }

    /** Mock output: the required fields, plus whatever the Normalize steps it feeds read, and values passing each check. */
    private JsonObject mockOutput(JobNode node) {
        JsonObject mock = new JsonObject();
        mock.addProperty("mock", true);
        List<String> fields = new ArrayList<>(node.outcome.requiredFields);
        for (JobLink link : space.links) {
            if (!link.from.equals(node.id)) continue;
            JobNode to = space.node(link.to);
            if (to != null && to.transform != null) for (KeyValue m : to.transform.mappings) fields.add(m.value);
        }
        for (String field : fields) if (!field.isEmpty()) Values.insert(new JsonPrimitive("mock"), field, mock);
        for (ValueAssertion a : node.outcome.assertions) {
            if (a.path.isEmpty()) continue;
            String expected = ApiTester.substitute(a.value, node.arguments);
            Double number;
            try {
                number = expected.trim().isEmpty() ? null : Double.valueOf(expected.trim());
            } catch (NumberFormatException e) {
                number = null;
            }
            JsonElement value = switch (a.op) {
                case EQUALS, LESS_OR_EQUAL, GREATER_OR_EQUAL -> number != null ? new JsonPrimitive(number) : new JsonPrimitive(expected);
                case NOT_EQUALS -> number != null ? new JsonPrimitive(number + 1) : new JsonPrimitive("not " + expected);
                case LESS -> new JsonPrimitive(number != null ? (number > 0 ? number / 2 : number - 1) : 0);
                case GREATER -> new JsonPrimitive(number != null ? (number > 0 ? number * 2 : number + 1) : 1);
                case CONTAINS -> {
                    JsonArray arr = new JsonArray();
                    arr.add(expected);
                    yield arr;
                }
                case EXISTS -> new JsonPrimitive("mock");
            };
            Values.insert(value, a.path, mock);
        }
        return mock;
    }

    private record Attempt(ApiTester.Result result, String summary) {}

    @FunctionalInterface
    private interface Body {
        Attempt call() throws InterruptedException;
    }

    /** Runs a live call or script with the step's exception handling: retries with backoff, asking, fallback, skip, abort. */
    private void attempt(JobNode node, Body body) {
        ExceptionPolicy policy = node.exceptions;
        int attempt = 0;
        while (true) {
            checkCancelled();
            Attempt a;
            try {
                a = body.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
            checkCancelled();
            ApiTester.Result result = a.result();
            if (result.passed()) {
                finish(node, NodeState.SUCCEEDED, result.json() != null ? result.json() : new JsonPrimitive(result.body()), a.summary());
                return;
            }
            String reason = result.error() != null ? result.error()
                    : String.join("; ", result.checks().stream().filter(c -> !c.passed()).map(ApiTester.Check::label).toList());
            if (policy.onFailure == FailureAction.RETRY && attempt < policy.maxRetries) {
                double delay = policy.retryDelaySeconds * (policy.exponentialBackoff ? Math.pow(2, attempt) : 1);
                attempt++;
                write(Level.WARNING, "Attempt " + attempt + " failed (" + reason + "); retrying in " + (int) delay + " s…", node.id);
                sleep(delay);
                continue;
            }
            if (policy.onFailure == FailureAction.ESCALATE_TO_USER) {
                String reply = ask(node, "“" + node.title + "” failed", reason, new JsonPrimitive(result.body()),
                        List.of(new Option("Retry", true, false), new Option("Skip", false, false), new Option("Abort", false, true)));
                switch (reply) {
                    case "Retry" -> { continue; }
                    case "Skip" -> {
                        finish(node, NodeState.SKIPPED, null, "Skipped after failure: " + reason, Level.WARNING);
                        return;
                    }
                    default -> throw new Abort();
                }
            }
            handleFailure(node, reason, null);
            return;
        }
    }

    /** Applies the step's exception handling (other than retry) to a failure. */
    private void handleFailure(JobNode node, String message, JsonElement partial) {
        switch (node.exceptions.onFailure) {
            case SKIP -> finish(node, NodeState.SKIPPED, partial, "Skipped: " + message, Level.WARNING);
            case USE_FALLBACK -> finish(node, NodeState.WARNING, new JsonPrimitive(node.exceptions.fallbackValue),
                    "Using fallback value “" + node.exceptions.fallbackValue + "”: " + message);
            case ESCALATE_TO_USER -> {
                String reply = ask(node, "“" + node.title + "” failed", message, partial,
                        List.of(new Option("Continue", true, false), new Option("Abort", false, true)));
                if (reply.equals("Continue")) finish(node, NodeState.WARNING, partial, "Continued after failure: " + message);
                else throw new Abort();
            }
            case ABORT_RUN -> {
                blocked.add(node.id);
                finish(node, NodeState.FAILED, null, message + " — aborting the run.");
                throw new Abort();
            }
            case RETRY -> fail(node, message);
        }
    }

    // MARK: Helpers

    private void write(Level level, String message, String node) {
        log.add(new LogEntry(Instant.now(), level, node, message));
        changed();
    }

    /** Fills {{name}} and {{name.count}} from a step's inputs (or paths inside them). */
    public static String fill(String template, JsonObject inputs) {
        String out = template;
        int from = 0;
        while (true) {
            int open = out.indexOf("{{", from);
            if (open < 0) break;
            int close = out.indexOf("}}", open + 2);
            if (close < 0) break;
            String path = out.substring(open + 2, close).trim();
            boolean wantsCount = path.endsWith(".count");
            if (wantsCount) path = path.substring(0, path.length() - 6);
            String text = "?";
            JsonElement value = Values.lookup(path, inputs);
            if (value != null) {
                int size = value.isJsonArray() ? value.getAsJsonArray().size() : value.isJsonObject() ? value.getAsJsonObject().size() : -1;
                if (wantsCount) text = Integer.toString(size < 0 ? 1 : size);
                else if (Values.isString(value)) text = value.getAsString();
                else if (value.isJsonPrimitive()) text = Values.describe(value);
                else text = (size < 0 ? 0 : size) + " items";
            }
            out = out.substring(0, open) + text + out.substring(close + 2);
            from = open + text.length();
        }
        return out;
    }

    /** How long the last run took, e.g. " in 3.2 s", or "". */
    public String duration() {
        Instant a = startedAt, b = finishedAt;
        if (a == null || b == null) return "";
        return String.format(java.util.Locale.ROOT, " in %.1f s", Duration.between(a, b).toMillis() / 1000.0);
    }
}
