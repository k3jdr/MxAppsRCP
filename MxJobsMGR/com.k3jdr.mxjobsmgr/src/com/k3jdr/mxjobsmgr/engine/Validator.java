package com.k3jdr.mxjobsmgr.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobLink;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.NodeKind;
import com.k3jdr.mxjobsmgr.model.Specs.Argument;
import com.k3jdr.mxjobsmgr.model.Specs.ExecutionMode;
import com.k3jdr.mxjobsmgr.model.Specs.ScriptSpec;

/**
 * Checks a jobspace for configuration gaps (Validation.swift): an execution without an approval upstream, API reads
 * without a subscription, path, secure account or key, required arguments without a value, unlinked nodes, links
 * that skip the agent layer, invalid base URLs.
 */
public final class Validator {
    private Validator() {}

    public enum Severity {
        INFO("ⓘ", 0x2F6FDB), WARNING("⚠", 0xD9A400), ERROR("⛔", 0xD03030);

        public final String symbol;
        public final int rgb;

        Severity(String symbol, int rgb) {
            this.symbol = symbol;
            this.rgb = rgb;
        }
    }

    public record Issue(Severity severity, String message, String nodeID, String subscriptionID) {}

    /** Why a subscription that needs a key can't send one: it names no secure account, or its account has no service key. */
    public static String missingKey(ApiSubscription sub) {
        return sub.secureAccountID == null
                ? "“" + sub.name + "” has no secure account: choose the one whose key it sends under API Subscriptions (the keys are in MxAcctMGR ▸ Secure Accounts)."
                : "“" + sub.secureAccountName + "” has no service key: add it in MxAcctMGR ▸ Secure Accounts.";
    }

    private static Issue keyIssue(JobNode node, ApiSubscription sub) {
        if (!sub.auth.requiresKey() || SecureKeys.hasKey(sub)) return null;
        String message = sub.secureAccountID == null ? "no secure account chosen for “" + sub.name + "”"
                : "no service key in Secure Accounts for “" + sub.name + "”";
        return new Issue(Severity.WARNING, "“" + node.title + "”: " + message + ".", node.id, sub.id);
    }

    public static List<Issue> check(Jobspace space) {
        List<Issue> issues = new ArrayList<>();
        Set<String> linked = new HashSet<>();
        for (JobLink l : space.links) {
            linked.add(l.from);
            linked.add(l.to);
        }

        for (JobNode node : space.nodes) {
            if (!linked.contains(node.id) && space.nodes.size() > 1)
                issues.add(new Issue(Severity.WARNING, "“" + node.title + "” is not linked to anything.", node.id, null));
            for (Argument arg : node.arguments) {
                if (arg.required && arg.value.trim().isEmpty())
                    issues.add(new Issue(Severity.WARNING, "“" + node.title + "”: required argument “" + arg.name + "” has no value.", node.id, null));
            }
            switch (node.kind) {
                case EXECUTE_REQUEST -> {
                    Set<String> upstream = space.upstream(node.id);
                    boolean approved = space.nodes.stream().anyMatch(n -> n.kind == NodeKind.APPROVAL_GATE && upstream.contains(n.id));
                    if (!approved)
                        issues.add(new Issue(Severity.ERROR, "“" + node.title + "” runs without a User Approval step before it.", node.id, null));
                    if (node.execution != null && node.execution.mode == ExecutionMode.LIVE)
                        issues.add(new Issue(Severity.INFO, "“" + node.title + "” sends live requests.", node.id, null));
                }
                case API_READ -> {
                    if (node.api == null) break;
                    ApiSubscription sub = space.subscription(node.api.subscriptionID);
                    if (sub != null) {
                        Issue k = keyIssue(node, sub);
                        if (k != null) issues.add(k);
                    } else {
                        issues.add(new Issue(Severity.ERROR, "“" + node.title + "” has no API subscription.", node.id, null));
                    }
                    if (node.api.path.isEmpty())
                        issues.add(new Issue(Severity.WARNING, "“" + node.title + "” has no endpoint path.", node.id, null));
                }
                case PYTHON_SCRIPT -> {
                    ScriptSpec spec = node.script != null ? node.script : new ScriptSpec();
                    if (spec.code.isBlank())
                        issues.add(new Issue(Severity.ERROR, "“" + node.title + "” has no Python code.", node.id, null));
                    if (PythonRunner.locate(spec.interpreter) == null)
                        issues.add(new Issue(Severity.WARNING, "“" + node.title + "”: no Python interpreter found.", node.id, null));
                    ApiSubscription sub = space.subscription(spec.subscriptionID);
                    if (sub != null) {
                        Issue k = keyIssue(node, sub);
                        if (k != null) issues.add(k);
                    }
                }
                case APPROVAL_GATE -> {
                    if (node.approval == null || node.approval.approver.isEmpty())
                        issues.add(new Issue(Severity.INFO, "“" + node.title + "” has no approver named.", node.id, null));
                }
                default -> { }
            }
        }

        for (JobLink link : space.links) {
            JobNode a = space.node(link.from), b = space.node(link.to);
            if (a == null || b == null) continue;
            if (Math.abs(a.layer().number - b.layer().number) > 1)
                issues.add(new Issue(Severity.WARNING, "“" + a.title + "” → “" + b.title + "” skips the agent layer.", a.id, null));
        }

        for (ApiSubscription sub : space.subscriptions) {
            if (ProviderGuide.host(sub.baseURL) == null) {
                issues.add(new Issue(Severity.ERROR, "Subscription “" + sub.name + "” has no valid base URL.", null, sub.id));
                continue;
            }
            String base = ProviderGuide.matching(sub).baseURL();
            if (base != null && !ProviderGuide.sameBase(sub.baseURL, base)) {
                issues.add(new Issue(Severity.WARNING, "Subscription “" + sub.name + "”: the base URL should be " + base
                        + "; put the endpoint and its parameters on each API Data Read step.", null, sub.id));
            } else if (sub.baseURL.contains("?")) {
                issues.add(new Issue(Severity.WARNING, "Subscription “" + sub.name
                        + "”: the base URL has parameters (?…); put them on each API Data Read step instead.", null, sub.id));
            }
        }
        issues.sort(Comparator.comparing((Issue i) -> i.severity()).reversed());
        return issues;
    }

    /** The worst severity among a node's issues, or null. */
    public static Severity worst(List<Issue> issues, String nodeID) {
        Severity worst = null;
        for (Issue i : issues) {
            if (nodeID.equals(i.nodeID()) && (worst == null || i.severity().compareTo(worst) > 0)) worst = i.severity();
        }
        return worst;
    }
}
