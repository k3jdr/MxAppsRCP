package com.k3jdr.mxacctmgr;

import java.util.Arrays;
import java.util.List;

/**
 * The window's screens, in the sidebar's order (Previous and Next Step follow it): PortStore.swift's {@code Screen}.
 * Welcome and Choose Your Path aren't in the sidebar: the Guide menu opens them.
 */
public enum Screen {
    CHECKING("checking", "Checking Accounts", Group.BANKING),
    CARDS("cards", "Credit Card Accounts", Group.BANKING),
    EXPENSE_LEDGERS("expenseLedgers", "Expense Ledgers", Group.BANKING),
    EXPENSES("expenses", "Target Accounts", Group.BANKING),
    SECURE_ACCOUNTS("secureAccounts", "Secure Accounts", Group.BANKING),
    WELCOME("welcome", "Welcome", Group.START),
    CHOOSE_PATH("choosePath", "Choose Your Path", Group.START),
    MY_PORTFOLIO("myPortfolio", "My Holdings", Group.MINE),
    MY_BENCHMARK("myBenchmark", "My Benchmark", Group.MINE),
    MY_RISK("myRisk", "Risk Today", Group.MINE),
    MY_IMPROVE("myImprove", "Optimize or Track", Group.MINE),
    MY_TRADES("myTrades", "Trades & Rebalancing", Group.MINE),
    MY_ATTRIBUTION("myAttribution", "Attribution", Group.MINE),
    REPORT_LIBRARY("reportLibrary", "Report Library", Group.REPORTS),
    MORNING_BRIEFING("morningBriefing", "Morning Briefing", Group.REPORTS),
    EARNINGS_CALENDAR("earningsCalendar", "Earnings Calendar", Group.REPORTS),
    EARNINGS_DAY("earningsDay", "Earnings by Day", Group.REPORTS),
    VIX("vix", "CBOE Volatility Index (VIX)", Group.REPORTS),
    COMPUTE("compute", "Compute Hyperscalers Tracking", Group.REPORTS),
    REPORT_WALKTHROUGH("reportWalkthrough", "Report Walkthrough", Group.REPORTS),
    WHY_OPTIMIZE("whyOptimize", "Why Optimize?", Group.REPORTS),
    WHY_ATTRIBUTION("whyAttribution", "Why Attribution?", Group.REPORTS),
    ACTIVE_VS_PASSIVE("activeVsPassive", "Active vs Passive", Group.REPORTS),
    SAMPLES("samples", "Sample Portfolios", Group.REPORTS),
    SPENDING("spending", "Spending Analysis", Group.LIBRARY),
    SECURITIES("securities", "SRM Table", Group.LIBRARY),
    SERVICES("services", "Layer 3 Services", Group.LIBRARY);

    public enum Group {
        BANKING("Banking"), START("Start Here"), MINE("Investments"), REPORTS("Knowledge Library"), LIBRARY("Reporting");

        public final String title;

        Group(String title) { this.title = title; }

        /** The sidebar's sections (Start Here's screens are in the Guide menu only). */
        public static final List<Group> SIDEBAR = List.of(BANKING, MINE, REPORTS, LIBRARY);
    }

    /** The Swift raw value: Quick Help, lessons, launch arguments ({@code -screen myImprove}) and settings use it. */
    public final String id;
    public final String title;
    public final Group group;

    Screen(String id, String title, Group group) {
        this.id = id;
        this.title = title;
        this.group = group;
    }

    public static final List<Screen> MY_PATH = List.of(MY_PORTFOLIO, MY_BENCHMARK, MY_RISK, MY_IMPROVE, MY_TRADES, MY_ATTRIBUTION);

    /** The step number on the Investments path, or 0. */
    public int step() {
        int i = MY_PATH.indexOf(this);
        return i + 1;
    }

    /** The pages that only teach: Settings ▸ Guidance ▸ Minimal leaves them out. */
    public boolean lessonOnly() {
        return this == WELCOME || this == CHOOSE_PATH || this == WHY_OPTIMIZE || this == WHY_ATTRIBUTION || this == ACTIVE_VS_PASSIVE;
    }

    public static Screen of(String id) {
        return Arrays.stream(values()).filter(s -> s.id.equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    /** The screens Previous and Next Step walk: the sidebar's, in its order (less those Settings ▸ Guidance hides). */
    public static List<Screen> guide() {
        return Arrays.stream(values()).filter(s -> Group.SIDEBAR.contains(s.group) && Settings.shows(s)).toList();
    }

    public Screen next() {
        List<Screen> g = guide();
        int i = g.indexOf(this);
        return i >= 0 && i + 1 < g.size() ? g.get(i + 1) : null;
    }

    public Screen previous() {
        List<Screen> g = guide();
        int i = g.indexOf(this);
        return i > 0 ? g.get(i - 1) : null;
    }
}
