package com.k3jdr.mxjobsmgr.model;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.k3jdr.mxjobsmgr.model.Specs.AgentSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ApiCallSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ApprovalSpec;
import com.k3jdr.mxjobsmgr.model.Specs.Argument;
import com.k3jdr.mxjobsmgr.model.Specs.ExceptionPolicy;
import com.k3jdr.mxjobsmgr.model.Specs.ExecutionSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ExpectedOutcome;
import com.k3jdr.mxjobsmgr.model.Specs.FailureAction;
import com.k3jdr.mxjobsmgr.model.Specs.ReportSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ScheduleSpec;
import com.k3jdr.mxjobsmgr.model.Specs.ScriptSpec;
import com.k3jdr.mxjobsmgr.model.Specs.StepLink;
import com.k3jdr.mxjobsmgr.model.Specs.TransformSpec;

/** A step of the diagram: its kind, place, and settings. */
public final class JobNode implements J.Writable {
    public String id = J.newId();
    public NodeKind kind;
    public String title;
    public String notes = "";
    public double x;
    public double y;
    public boolean enabled = true;
    /** Debug runs pause before this node (when breakpoints are on). */
    public boolean breakpoint;

    public List<Argument> arguments = new ArrayList<>();
    public ExpectedOutcome outcome = new ExpectedOutcome();
    public ExceptionPolicy exceptions = new ExceptionPolicy();

    public ScheduleSpec schedule;
    public AgentSpec agent;
    public ApprovalSpec approval;
    public ExecutionSpec execution;
    public ReportSpec report;
    public ApiCallSpec api;
    public TransformSpec transform;
    public ScriptSpec script;
    /** The step's own link and hover text (right-click the step); without one it shows its palette element's. */
    public StepLink link;

    private JobNode() {}

    /** A new step of {@code kind} with the kind's settings, as the Mac app makes one. */
    public JobNode(NodeKind kind, String title, double x, double y) {
        this.kind = kind;
        this.title = title != null ? title : kind.title;
        this.x = x;
        this.y = y;
        switch (kind) {
            case TRIGGER -> schedule = new ScheduleSpec();
            case INSTRUCTION_SET, AGENT_TASK, AGGREGATE, DECISION -> agent = new AgentSpec();
            case APPROVAL_GATE -> approval = new ApprovalSpec();
            case EXECUTE_REQUEST -> execution = new ExecutionSpec();
            case OUTCOME_REPORT -> report = new ReportSpec();
            case API_READ -> api = new ApiCallSpec();
            case DATA_NORMALIZE -> transform = new TransformSpec();
            case PYTHON_SCRIPT -> script = new ScriptSpec();
        }
        if (kind == NodeKind.EXECUTE_REQUEST || kind == NodeKind.APPROVAL_GATE) {
            exceptions.onFailure = FailureAction.ESCALATE_TO_USER;
            exceptions.maxRetries = 0;
        }
    }

    public Layer layer() { return kind.layer; }

    /** Reads a node; null when it has no valid kind. */
    public static JobNode read(JsonObject o) {
        NodeKind kind = NodeKind.of(J.str(o, "kind", ""));
        if (kind == null) return null;
        JobNode n = new JobNode();
        n.kind = kind;
        n.id = J.uuid(o, "id", n.id);
        n.title = J.str(o, "title", kind.title);
        n.notes = J.str(o, "notes", "");
        n.x = J.dbl(o, "x", 0);
        n.y = J.dbl(o, "y", 0);
        n.enabled = J.bool(o, "enabled", true);
        n.breakpoint = J.bool(o, "breakpoint", false);
        n.arguments = J.list(o, "arguments", Argument::read);
        n.outcome = ExpectedOutcome.read(J.obj(o, "outcome"));
        n.exceptions = ExceptionPolicy.read(J.obj(o, "exceptions"));
        JsonObject s;
        if ((s = J.obj(o, "schedule")) != null) n.schedule = ScheduleSpec.read(s);
        if ((s = J.obj(o, "agent")) != null) n.agent = AgentSpec.read(s);
        if ((s = J.obj(o, "approval")) != null) n.approval = ApprovalSpec.read(s);
        if ((s = J.obj(o, "execution")) != null) n.execution = ExecutionSpec.read(s);
        if ((s = J.obj(o, "report")) != null) n.report = ReportSpec.read(s);
        if ((s = J.obj(o, "api")) != null) n.api = ApiCallSpec.read(s);
        if ((s = J.obj(o, "transform")) != null) n.transform = TransformSpec.read(s);
        if ((s = J.obj(o, "script")) != null) n.script = ScriptSpec.read(s);
        if ((s = J.obj(o, "link")) != null) n.link = StepLink.read(s);
        return n;
    }

    @Override
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind.raw());
        o.addProperty("title", title);
        o.addProperty("notes", notes);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("enabled", enabled);
        o.addProperty("breakpoint", breakpoint);
        o.add("arguments", J.array(arguments));
        o.add("outcome", outcome.toJson());
        o.add("exceptions", exceptions.toJson());
        if (schedule != null) o.add("schedule", schedule.toJson());
        if (agent != null) o.add("agent", agent.toJson());
        if (approval != null) o.add("approval", approval.toJson());
        if (execution != null) o.add("execution", execution.toJson());
        if (report != null) o.add("report", report.toJson());
        if (api != null) o.add("api", api.toJson());
        if (transform != null) o.add("transform", transform.toJson());
        if (script != null) o.add("script", script.toJson());
        if (link != null) o.add("link", link.toJson());
        return o;
    }

    /** A deep copy (same id). */
    public JobNode copy() {
        return read(toJson());
    }

    // Kind-specific settings, made when a file left them out (the inspector's Binding(default:)).

    public ScheduleSpec schedule() { if (schedule == null) schedule = new ScheduleSpec(); return schedule; }
    public AgentSpec agent() { if (agent == null) agent = new AgentSpec(); return agent; }
    public ApprovalSpec approval() { if (approval == null) approval = new ApprovalSpec(); return approval; }
    public ExecutionSpec execution() { if (execution == null) execution = new ExecutionSpec(); return execution; }
    public ReportSpec report() { if (report == null) report = new ReportSpec(); return report; }
    public ApiCallSpec api() { if (api == null) api = new ApiCallSpec(); return api; }
    public TransformSpec transform() { if (transform == null) transform = new TransformSpec(); return transform; }
    public ScriptSpec script() { if (script == null) script = new ScriptSpec(); return script; }
}
