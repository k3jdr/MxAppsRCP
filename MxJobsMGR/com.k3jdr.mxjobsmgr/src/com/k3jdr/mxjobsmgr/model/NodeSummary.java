package com.k3jdr.mxjobsmgr.model;

import java.util.List;

import com.k3jdr.mxjobsmgr.model.Specs.ExecutionMode;
import com.k3jdr.mxjobsmgr.model.Specs.ScriptSpec;

/** One line describing a node's configuration (canvas and exports). */
public final class NodeSummary {
    private NodeSummary() {}

    public static String text(JobNode node, Jobspace space) {
        switch (node.kind) {
            case TRIGGER:
                return node.schedule != null ? node.schedule.summary() : "";
            case API_READ: {
                if (node.api == null) return "";
                ApiSubscription sub = space.subscription(node.api.subscriptionID);
                return node.api.method.raw() + " " + (node.api.path.isEmpty() ? "/" : node.api.path) + " · " + (sub != null ? sub.name : "No subscription");
            }
            case PYTHON_SCRIPT: {
                ScriptSpec spec = node.script != null ? node.script : new ScriptSpec();
                long lines = spec.code.lines().filter(l -> !l.isEmpty()).count();
                ApiSubscription sub = space.subscription(spec.subscriptionID);
                return "python · " + lines + " line" + (lines == 1 ? "" : "s") + (sub != null ? " · " + sub.name : "");
            }
            case APPROVAL_GATE: {
                String who = node.approval != null ? node.approval.approver : "";
                return who.isEmpty() ? "Approver not set" : "By " + who;
            }
            case EXECUTE_REQUEST: {
                if (node.execution == null) return "";
                var e = node.execution;
                return (e.mode == ExecutionMode.LIVE ? "LIVE" : e.mode == ExecutionMode.PAPER ? "Paper" : "Dry run") + (e.target.isEmpty() ? "" : " · " + e.target);
            }
            case OUTCOME_REPORT: {
                String to = node.report != null ? node.report.recipients : "";
                return to.isEmpty() ? "Report" : "To " + to;
            }
            case DATA_NORMALIZE:
                return (node.transform != null ? node.transform.mappings.size() : 0) + " field mappings";
            default: {
                String out = node.outcome.outputName;
                List<String> inputs = space.inputNames(node.id);
                if (!inputs.isEmpty() || !out.isEmpty())
                    return String.join(", ", inputs) + (inputs.isEmpty() ? "" : " → ") + (out.isEmpty() ? "…" : out);
                return node.agent != null && !node.agent.instructions.isEmpty() ? node.agent.instructions : node.kind.summary;
            }
        }
    }
}
