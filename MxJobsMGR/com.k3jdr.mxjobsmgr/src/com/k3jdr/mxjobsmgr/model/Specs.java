package com.k3jdr.mxjobsmgr.model;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * The settings a step carries (JobspaceModel.swift): arguments, expected outcome, exception handling and the
 * kind-specific settings, with their enums. Each reads with the Mac app's defaults and writes every key it has.
 */
public final class Specs {
    private Specs() {}

    // MARK: - Enums

    public enum ArgumentType implements J.Raw {
        TEXT("text"), NUMBER("number"), DATE("date"), SYMBOLS("symbols"), BOOLEAN("boolean");
        private final String raw;
        ArgumentType(String raw) { this.raw = raw; }
        @Override public String raw() { return raw; }
    }

    public enum AssertionOp implements J.Raw {
        EQUALS("=="), NOT_EQUALS("!="), LESS("<"), LESS_OR_EQUAL("<="), GREATER(">"), GREATER_OR_EQUAL(">="),
        CONTAINS("contains"), EXISTS("exists");
        private final String raw;
        AssertionOp(String raw) { this.raw = raw; }
        @Override public String raw() { return raw; }
    }

    public enum FailureAction implements J.Raw {
        RETRY("retry", "Retry, then fail"), SKIP("skip", "Skip and continue"), USE_FALLBACK("useFallback", "Use fallback value"),
        ESCALATE_TO_USER("escalateToUser", "Ask the user"), ABORT_RUN("abortRun", "Abort the run");
        private final String raw;
        public final String title;
        FailureAction(String raw, String title) { this.raw = raw; this.title = title; }
        @Override public String raw() { return raw; }
    }

    public enum ScheduleMode implements J.Raw {
        MANUAL("manual", "Manually"), DAILY("daily", "Daily"), WEEKDAYS("weekdays", "Weekdays"), INTERVAL("interval", "Every N minutes");
        private final String raw;
        public final String title;
        ScheduleMode(String raw, String title) { this.raw = raw; this.title = title; }
        @Override public String raw() { return raw; }
    }

    public enum ApprovalChannel implements J.Raw {
        IN_APP("inApp", "In app"), EMAIL("email", "Email"), MESSAGE("message", "Message");
        private final String raw;
        public final String title;
        ApprovalChannel(String raw, String title) { this.raw = raw; this.title = title; }
        @Override public String raw() { return raw; }
    }

    public enum ExecutionMode implements J.Raw {
        DRY_RUN("dryRun", "Dry run (no requests sent)"), PAPER("paper", "Paper / sandbox account"), LIVE("live", "Live");
        private final String raw;
        public final String title;
        ExecutionMode(String raw, String title) { this.raw = raw; this.title = title; }
        @Override public String raw() { return raw; }
    }

    public enum HttpMethod implements J.Raw {
        GET, POST;
        @Override public String raw() { return name(); }
    }

    public enum AuthStyle implements J.Raw {
        NONE("none", "No key"), QUERY_PARAMETER("queryParameter", "Key as query parameter"), HEADER("header", "Key in header"),
        BEARER("bearer", "Bearer token"), LOCAL_SERVICE("localService", "Local service (optional token)");
        private final String raw;
        public final String title;
        AuthStyle(String raw, String title) { this.raw = raw; this.title = title; }
        @Override public String raw() { return raw; }
        /** Calls fail without a key. A local service sends its token only when its secure account has one. */
        public boolean requiresKey() { return this == QUERY_PARAMETER || this == HEADER || this == BEARER; }
    }

    /** The IDE a subscription's source project opens in. The raw values are the Mac app's; the titles Windows'. */
    public enum IdeChoice implements J.Raw {
        VSCODE("vscode", "Visual Studio Code"), INTELLIJ("intellij", "IntelliJ IDEA"), CLION("clion", "CLion"),
        XCODE("xcode", "Xcode (macOS only)"), FINDER("finder", "Explorer");
        private final String raw;
        public final String title;
        IdeChoice(String raw, String title) { this.raw = raw; this.title = title; }
        @Override public String raw() { return raw; }
    }

    public enum OperationsAction implements J.Raw {
        INTERACTIVE("interactive", "Interactive — ask for approvals", "Interactive"),
        AUTO_APPROVE("autoApprove", "Auto-approve (rehearsal)", "Auto-approve"),
        SKIP("skip", "Skip Layer 1", "Skip");
        private final String raw;
        public final String title, shortTitle;
        OperationsAction(String raw, String title, String shortTitle) { this.raw = raw; this.title = title; this.shortTitle = shortTitle; }
        @Override public String raw() { return raw; }
    }

    public enum AgentsAction implements J.Raw {
        RUN("run", "Run agents (simulated)", "Run"), SKIP("skip", "Skip Layer 2", "Skip");
        private final String raw;
        public final String title, shortTitle;
        AgentsAction(String raw, String title, String shortTitle) { this.raw = raw; this.title = title; this.shortTitle = shortTitle; }
        @Override public String raw() { return raw; }
    }

    public enum DataAction implements J.Raw {
        LIVE("live", "Live API calls", "Live"), MOCK("mock", "Mock data (no network)", "Mock"), SKIP("skip", "Skip Layer 3", "Skip");
        private final String raw;
        public final String title, shortTitle;
        DataAction(String raw, String title, String shortTitle) { this.raw = raw; this.title = title; this.shortTitle = shortTitle; }
        @Override public String raw() { return raw; }
    }

    // MARK: - Shared node settings

    /** A named key/value pair (query parameters, headers, field mappings). */
    public static final class KeyValue implements J.Writable {
        public String id = J.newId();
        public String key = "";
        public String value = "";

        public KeyValue() {}

        public KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public static KeyValue read(JsonObject o) {
            KeyValue kv = new KeyValue();
            kv.id = J.uuid(o, "id", kv.id);
            kv.key = J.str(o, "key", "");
            kv.value = J.str(o, "value", "");
            return kv;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("key", key);
            o.addProperty("value", value);
            return o;
        }
    }

    /** An argument a node expects. Its value can be used as {{name}} in API paths, parameters and bodies. */
    public static final class Argument implements J.Writable {
        public String id = J.newId();
        public String name = "";
        public ArgumentType type = ArgumentType.TEXT;
        public String value = "";
        public boolean required;

        public static Argument read(JsonObject o) {
            Argument a = new Argument();
            a.id = J.uuid(o, "id", a.id);
            a.name = J.str(o, "name", "");
            a.type = J.enumOf(o, "type", ArgumentType.TEXT);
            a.value = J.str(o, "value", "");
            a.required = J.bool(o, "required", false);
            return a;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("type", type.raw());
            o.addProperty("value", value);
            o.addProperty("required", required);
            return o;
        }
    }

    /** A check on one value of a step's result: {@code path op value}. The value may use {{argument}}. */
    public static final class ValueAssertion implements J.Writable {
        public String id = J.newId();
        public String path = "";
        public AssertionOp op = AssertionOp.EQUALS;
        public String value = "";

        public static ValueAssertion read(JsonObject o) {
            ValueAssertion a = new ValueAssertion();
            a.id = J.uuid(o, "id", a.id);
            a.path = J.str(o, "path", "");
            a.op = J.enumOf(o, "op", AssertionOp.EQUALS);
            a.value = J.str(o, "value", "");
            return a;
        }

        public String label() {
            return op == AssertionOp.EXISTS ? path + " exists" : path + " " + op.raw() + " " + value;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("path", path);
            o.addProperty("op", op.raw());
            o.addProperty("value", value);
            return o;
        }
    }

    /** What a node must produce to count as successful. */
    public static final class ExpectedOutcome implements J.Writable {
        public String description = "";
        /** For API reads: accepted HTTP status codes, e.g. "200-299" or "200,304". */
        public String successStatus = "200-299";
        /** Dotted JSON paths that must be present in the result. */
        public List<String> requiredFields = new ArrayList<>();
        /** Name under which the result is published to downstream nodes. */
        public String outputName = "";
        public List<ValueAssertion> assertions = new ArrayList<>();

        public static ExpectedOutcome read(JsonObject o) {
            ExpectedOutcome e = new ExpectedOutcome();
            if (o == null) return e;
            e.description = J.str(o, "description", "");
            e.successStatus = J.str(o, "successStatus", "200-299");
            e.requiredFields = J.strings(o, "requiredFields");
            e.outputName = J.str(o, "outputName", "");
            e.assertions = J.list(o, "assertions", ValueAssertion::read);
            return e;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("description", description);
            o.addProperty("successStatus", successStatus);
            o.add("requiredFields", J.stringArray(requiredFields));
            o.addProperty("outputName", outputName);
            o.add("assertions", J.array(assertions));
            return o;
        }
    }

    /** How a node reacts when it fails or times out. */
    public static final class ExceptionPolicy implements J.Writable {
        public FailureAction onFailure = FailureAction.RETRY;
        public int maxRetries = 2;
        public int retryDelaySeconds = 5;
        public boolean exponentialBackoff = true;
        public int timeoutSeconds = 30;
        public String fallbackValue = "";
        public boolean notifyUser = true;

        public static ExceptionPolicy read(JsonObject o) {
            ExceptionPolicy p = new ExceptionPolicy();
            if (o == null) return p;
            p.onFailure = J.enumOf(o, "onFailure", p.onFailure);
            p.maxRetries = J.integer(o, "maxRetries", p.maxRetries);
            p.retryDelaySeconds = J.integer(o, "retryDelaySeconds", p.retryDelaySeconds);
            p.exponentialBackoff = J.bool(o, "exponentialBackoff", p.exponentialBackoff);
            p.timeoutSeconds = J.integer(o, "timeoutSeconds", p.timeoutSeconds);
            p.fallbackValue = J.str(o, "fallbackValue", p.fallbackValue);
            p.notifyUser = J.bool(o, "notifyUser", p.notifyUser);
            return p;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("onFailure", onFailure.raw());
            o.addProperty("maxRetries", maxRetries);
            o.addProperty("retryDelaySeconds", retryDelaySeconds);
            o.addProperty("exponentialBackoff", exponentialBackoff);
            o.addProperty("timeoutSeconds", timeoutSeconds);
            o.addProperty("fallbackValue", fallbackValue);
            o.addProperty("notifyUser", notifyUser);
            return o;
        }
    }

    // MARK: - Kind-specific settings

    /** Layer 1 trigger: when the refresh runs. */
    public static final class ScheduleSpec implements J.Writable {
        public ScheduleMode mode = ScheduleMode.WEEKDAYS;
        public String time = "07:30";
        public String timeZone = "America/New_York";
        public int intervalMinutes = 60;

        public static ScheduleSpec read(JsonObject o) {
            ScheduleSpec s = new ScheduleSpec();
            s.mode = J.enumOf(o, "mode", s.mode);
            s.time = J.str(o, "time", s.time);
            s.timeZone = J.str(o, "timeZone", s.timeZone);
            s.intervalMinutes = J.integer(o, "intervalMinutes", s.intervalMinutes);
            return s;
        }

        public String summary() {
            return switch (mode) {
                case MANUAL -> "Run manually";
                case DAILY -> "Daily at " + time;
                case WEEKDAYS -> "Weekdays at " + time;
                case INTERVAL -> "Every " + intervalMinutes + " min";
            };
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("mode", mode.raw());
            o.addProperty("time", time);
            o.addProperty("timeZone", timeZone);
            o.addProperty("intervalMinutes", intervalMinutes);
            return o;
        }
    }

    /** Layer 1 instruction assembly and Layer 2 agents: what to do with the incoming data. */
    public static final class AgentSpec implements J.Writable {
        public String instructions = "";
        public String model = "";
        /** Output names of upstream nodes this node reads. */
        public List<String> inputs = new ArrayList<>();
        public String outputFormat = "";

        public static AgentSpec read(JsonObject o) {
            AgentSpec a = new AgentSpec();
            a.instructions = J.str(o, "instructions", "");
            a.model = J.str(o, "model", "");
            a.inputs = J.strings(o, "inputs");
            a.outputFormat = J.str(o, "outputFormat", "");
            return a;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("instructions", instructions);
            o.addProperty("model", model);
            o.add("inputs", J.stringArray(inputs));
            o.addProperty("outputFormat", outputFormat);
            return o;
        }
    }

    /** Layer 1 user approval gate. */
    public static final class ApprovalSpec implements J.Writable {
        public String approver = "";
        public ApprovalChannel channel = ApprovalChannel.IN_APP;
        public int expiresAfterMinutes = 120;
        public boolean requireComment;
        public String summaryTemplate = "";

        public static ApprovalSpec read(JsonObject o) {
            ApprovalSpec a = new ApprovalSpec();
            a.approver = J.str(o, "approver", a.approver);
            a.channel = J.enumOf(o, "channel", a.channel);
            a.expiresAfterMinutes = J.integer(o, "expiresAfterMinutes", a.expiresAfterMinutes);
            a.requireComment = J.bool(o, "requireComment", a.requireComment);
            a.summaryTemplate = J.str(o, "summaryTemplate", a.summaryTemplate);
            return a;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("approver", approver);
            o.addProperty("channel", channel.raw());
            o.addProperty("expiresAfterMinutes", expiresAfterMinutes);
            o.addProperty("requireComment", requireComment);
            o.addProperty("summaryTemplate", summaryTemplate);
            return o;
        }
    }

    /** Layer 1 execution request, sent only after approval. */
    public static final class ExecutionSpec implements J.Writable {
        public String target = "";
        public ExecutionMode mode = ExecutionMode.DRY_RUN;
        public boolean confirmBeforeSend = true;

        public static ExecutionSpec read(JsonObject o) {
            ExecutionSpec e = new ExecutionSpec();
            e.target = J.str(o, "target", e.target);
            e.mode = J.enumOf(o, "mode", e.mode);
            e.confirmBeforeSend = J.bool(o, "confirmBeforeSend", e.confirmBeforeSend);
            return e;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("target", target);
            o.addProperty("mode", mode.raw());
            o.addProperty("confirmBeforeSend", confirmBeforeSend);
            return o;
        }
    }

    /** Layer 1 outcome report. */
    public static final class ReportSpec implements J.Writable {
        public String recipients = "";
        public boolean detailed;
        public boolean includeFailures = true;

        public static ReportSpec read(JsonObject o) {
            ReportSpec r = new ReportSpec();
            r.recipients = J.str(o, "recipients", r.recipients);
            r.detailed = J.bool(o, "detailed", r.detailed);
            r.includeFailures = J.bool(o, "includeFailures", r.includeFailures);
            return r;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("recipients", recipients);
            o.addProperty("detailed", detailed);
            o.addProperty("includeFailures", includeFailures);
            return o;
        }
    }

    /** Layer 3 API read against a subscription. */
    public static final class ApiCallSpec implements J.Writable {
        public String subscriptionID;
        public HttpMethod method = HttpMethod.GET;
        public String path = "";
        public List<KeyValue> query = new ArrayList<>();
        public List<KeyValue> headers = new ArrayList<>();
        public String body = "";

        public static ApiCallSpec read(JsonObject o) {
            ApiCallSpec a = new ApiCallSpec();
            a.subscriptionID = J.uuid(o, "subscriptionID", null);
            a.method = J.enumOf(o, "method", HttpMethod.GET);
            a.path = J.str(o, "path", "");
            a.query = J.list(o, "query", KeyValue::read);
            a.headers = J.list(o, "headers", KeyValue::read);
            a.body = J.str(o, "body", "");
            return a;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (subscriptionID != null) o.addProperty("subscriptionID", subscriptionID);
            o.addProperty("method", method.raw());
            o.addProperty("path", path);
            o.add("query", J.array(query));
            o.add("headers", J.array(headers));
            o.addProperty("body", body);
            return o;
        }
    }

    /**
     * Layer 3 Python script. It gets {"arguments": {...}, "inputs": {...}, "step": title} as JSON on stdin and prints
     * its result as JSON on stdout. The optional subscription's key and base URL are passed as MXJOBS_API_KEY /
     * MXJOBS_BASE_URL (and under the subscription's key name when that is an environment-variable name).
     */
    public static final class ScriptSpec implements J.Writable {
        public static final String TEMPLATE = """
                import json, os, sys

                request = json.load(sys.stdin)        # {"arguments": {...}, "inputs": {...}, "step": "..."}
                args = request["arguments"]
                inputs = request["inputs"]            # outputs of the steps linked into this one
                api_key = os.environ.get("MXJOBS_API_KEY")  # from the step's subscription, if any

                result = {"arguments": args, "inputCount": len(inputs)}

                json.dump(result, sys.stdout)         # the step's output (must be JSON)
                """;

        public String code = TEMPLATE;
        /** Path of python; empty = the first one found on the PATH. */
        public String interpreter = "";
        public String subscriptionID;

        public static ScriptSpec read(JsonObject o) {
            ScriptSpec s = new ScriptSpec();
            s.code = J.str(o, "code", "");
            s.interpreter = J.str(o, "interpreter", "");
            s.subscriptionID = J.uuid(o, "subscriptionID", null);
            return s;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("code", code);
            o.addProperty("interpreter", interpreter);
            if (subscriptionID != null) o.addProperty("subscriptionID", subscriptionID);
            return o;
        }
    }

    /** Layer 3 normalisation: output field ← JSON path in the input. */
    public static final class TransformSpec implements J.Writable {
        public List<KeyValue> mappings = new ArrayList<>();

        public static TransformSpec read(JsonObject o) {
            TransformSpec t = new TransformSpec();
            t.mappings = J.list(o, "mappings", KeyValue::read);
            return t;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("mappings", J.array(mappings));
            return o;
        }
    }

    /**
     * A link with hover text (Shared/Links SecurityLink): where it goes, the words shown for it, its hover text (RTF,
     * kept as the Mac app's base64), and the title copied with an MxNoteONE link.
     */
    public static final class StepLink implements J.Writable {
        public String address = "";
        public String text;
        /** The hover text as RTF data, base64 — as the Mac app writes Data. */
        public String tip;
        public String title;

        public static StepLink read(JsonObject o) {
            String address = J.str(o, "address", null);
            if (address == null) return null;
            StepLink l = new StepLink();
            l.address = address;
            l.text = J.optStr(o, "text");
            l.tip = J.optStr(o, "tip");
            l.title = J.optStr(o, "title");
            return l;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("address", address);
            if (text != null) o.addProperty("text", text);
            if (tip != null) o.addProperty("tip", tip);
            if (title != null) o.addProperty("title", title);
            return o;
        }

        /** The hover text as plain text (the RTF's words), or null. */
        public String tipPlain() {
            return Rtf.plain(tip);
        }
    }

    // MARK: - Run settings

    public static final class RunSettings implements J.Writable {
        public OperationsAction operations = OperationsAction.INTERACTIVE;
        public AgentsAction agents = AgentsAction.RUN;
        public DataAction data = DataAction.MOCK;
        public boolean breakpointsEnabled = true;
        /** Stop the whole run at the first step that fails after its exception handling. */
        public boolean stopOnError = true;
        /** Pause between steps so the flow can be followed on the canvas (seconds). */
        public double stepDelay = 0.4;

        public static RunSettings read(JsonObject o) {
            RunSettings r = new RunSettings();
            if (o == null) return r;
            r.operations = J.enumOf(o, "operations", r.operations);
            r.agents = J.enumOf(o, "agents", r.agents);
            r.data = J.enumOf(o, "data", r.data);
            r.breakpointsEnabled = J.bool(o, "breakpointsEnabled", r.breakpointsEnabled);
            r.stopOnError = J.bool(o, "stopOnError", r.stopOnError);
            r.stepDelay = J.dbl(o, "stepDelay", r.stepDelay);
            return r;
        }

        @Override
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("operations", operations.raw());
            o.addProperty("agents", agents.raw());
            o.addProperty("data", data.raw());
            o.addProperty("breakpointsEnabled", breakpointsEnabled);
            o.addProperty("stopOnError", stopOnError);
            o.addProperty("stepDelay", stepDelay);
            return o;
        }
    }
}
