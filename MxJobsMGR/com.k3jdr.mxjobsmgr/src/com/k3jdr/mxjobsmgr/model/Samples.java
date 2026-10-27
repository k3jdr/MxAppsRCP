package com.k3jdr.mxjobsmgr.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.k3jdr.mxjobsmgr.model.Specs.Argument;

/**
 * The sample jobspaces (SampleLibrary.swift), shipped in the bundle's samples/ folder as the Mac app saves them in
 * MxJobsMGR/Samples. A sample opened as a new document gets new ids; a portfolio sample opened for another portfolio
 * or benchmark ({@code mxjobsmgr://sample/<id>?portfolio=&benchmark=}) has them filled in where the Mac app puts them.
 */
public final class Samples {
    private Samples() {}

    public static final String DEFAULT_PORTFOLIO = "DEMO_GROWTH";
    public static final String DEFAULT_BENCHMARK = "SP500";

    /** Sample id (ServiceCatalog.SAMPLES) → file. */
    public static final Map<String, String> FILES = Map.of(
            "srm-daily-refresh", "SRMDailyRefresh.jobspace",
            "market-refresh", "DailyMarketRefresh.jobspace",
            "active-optimization", "ActivePortfolioOptimization.jobspace",
            "passive-tracking", "PassiveIndexTracking.jobspace",
            "attribution", "PerformanceAttribution.jobspace");

    public static String text(String id) throws IOException {
        String file = FILES.get(id);
        if (file == null) throw new IOException("No sample “" + id + "”.");
        try (InputStream in = Samples.class.getResourceAsStream("/samples/" + file)) {
            if (in == null) throw new IOException("The sample " + file + " is missing from the app.");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A sample by id, as a new jobspace, for a portfolio / benchmark where the sample takes one. */
    public static Jobspace load(String id, String portfolio, String benchmark) throws IOException {
        Jobspace space = Jobspace.parse(text(id));
        String p = portfolio == null || portfolio.isBlank() ? null : portfolio.trim();
        String b = benchmark == null || benchmark.isBlank() ? null : benchmark.trim();
        switch (id) {
            case "active-optimization", "attribution" -> {
                if (p != null) replace(space, DEFAULT_PORTFOLIO, p);
                if (b != null) replace(space, id.equals("attribution") ? "its benchmark" : "the portfolio's benchmark", b);
                setArguments(space, p, b);
            }
            case "passive-tracking" -> {
                if (b != null) replace(space, DEFAULT_BENCHMARK, b);
                setArguments(space, p, b);
            }
            default -> { }
        }
        space.renewIds();
        return space;
    }

    private static void setArguments(Jobspace space, String portfolio, String benchmark) {
        for (JobNode n : space.nodes) {
            for (Argument a : n.arguments) {
                if (portfolio != null && a.name.equals("portfolio")) a.value = portfolio;
                if (benchmark != null && a.name.equals("benchmark")) a.value = benchmark;
            }
        }
    }

    /** Replaces the sample's default portfolio or benchmark in its texts (name, description, notes, approvals). */
    private static void replace(Jobspace space, String from, String to) {
        space.name = space.name.replace(from, to);
        space.description = space.description.replace(from, to);
        for (JobNode n : space.nodes) {
            n.title = n.title.replace(from, to);
            n.notes = n.notes.replace(from, to);
            if (n.approval != null) n.approval.summaryTemplate = n.approval.summaryTemplate.replace(from, to);
            if (n.agent != null) n.agent.instructions = n.agent.instructions.replace(from, to);
        }
    }
}
