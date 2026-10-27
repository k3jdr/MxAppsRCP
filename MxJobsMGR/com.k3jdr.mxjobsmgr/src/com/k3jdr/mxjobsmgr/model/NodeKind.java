package com.k3jdr.mxjobsmgr.model;

import java.util.ArrayList;
import java.util.List;

/** The kinds of step, each in its layer. The raw value is what the jobspace file says. */
public enum NodeKind implements J.Raw {
    TRIGGER("trigger", Layer.OPERATIONS, "Refresh Trigger", "Starts the day-to-day market data refresh", "⏱"),
    INSTRUCTION_SET("instructionSet", Layer.OPERATIONS, "Assemble Instructions", "Builds the instructions presented for approval", "☰"),
    APPROVAL_GATE("approvalGate", Layer.OPERATIONS, "User Approval", "Waits for the user to approve the instructions", "✔"),
    EXECUTE_REQUEST("executeRequest", Layer.OPERATIONS, "Execution Request", "Sends the approved instructions to be executed", "➤"),
    OUTCOME_REPORT("outcomeReport", Layer.OPERATIONS, "Outcome Report", "Reports the outcomes back to the user", "▤"),
    AGENT_TASK("agentTask", Layer.AGENTS, "Agent Task", "An agent gathering data points through Layer 3", "⚙"),
    AGGREGATE("aggregate", Layer.AGENTS, "Aggregate Results", "Combines the results of several agents", "⇶"),
    DECISION("decision", Layer.AGENTS, "Decision", "Routes the flow on a condition", "◇"),
    API_READ("apiRead", Layer.DATA, "API Data Read", "Calls an API endpoint of a subscription", "⇄"),
    PYTHON_SCRIPT("pythonScript", Layer.DATA, "Python Script", "Runs Python: arguments in as JSON, JSON out", "❯"),
    DATA_NORMALIZE("dataNormalize", Layer.DATA, "Normalize Data", "Maps raw response fields to named data points", "▦");

    private final String raw;
    public final Layer layer;
    public final String title;
    public final String summary;
    /** A glyph standing in for the Mac app's SF Symbol. */
    public final String symbol;

    NodeKind(String raw, Layer layer, String title, String summary, String symbol) {
        this.raw = raw;
        this.layer = layer;
        this.title = title;
        this.summary = summary;
        this.symbol = symbol;
    }

    @Override
    public String raw() { return raw; }

    public static NodeKind of(String raw) {
        for (NodeKind k : values()) if (k.raw.equals(raw)) return k;
        return null;
    }

    public static List<NodeKind> in(Layer layer) {
        List<NodeKind> out = new ArrayList<>();
        for (NodeKind k : values()) if (k.layer == layer) out.add(k);
        return out;
    }

    public boolean isAgentLike() {
        return this == INSTRUCTION_SET || this == AGENT_TASK || this == AGGREGATE || this == DECISION;
    }
}
