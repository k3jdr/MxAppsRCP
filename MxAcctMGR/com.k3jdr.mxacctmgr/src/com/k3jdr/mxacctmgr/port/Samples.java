package com.k3jdr.mxacctmgr.port;

import java.util.ArrayList;
import java.util.List;

/** The sample accounts' lessons, by security-master code (Samples.swift). */
public final class Samples {
    private Samples() {}

    /** In the order the Sample Portfolios screen lists them. */
    public static final List<SampleLesson> LESSONS = List.of(
            new SampleLesson("SAMPLE_CORE_SATELLITE", "circle.hexagongrid", PortfolioStyle.ACTIVE,
                    "An ordinary active account: a total-market index fund as the core and eight S&P 500 companies you believe in as satellites. The optimizer turns those beliefs into position sizes within a risk budget.",
                    List.of("Tracking error: how far from the index the satellites take you.", "Information ratio: forecast reward per unit of active risk.", "Attribution: whether the satellites' sectors or the stock picks drove the result.")),
            new SampleLesson("SAMPLE_EMPLOYER_STOCK", "building.2", PortfolioStyle.EITHER,
                    "Nearly half the account is the company you work for, bought over many years — the oldest shares at a small fraction of today's price. Your salary already depends on that company.",
                    List.of("Risk: how much of the account's volatility comes from one name.", "Trades & Taxes: the tax cost of diversifying (old lots carry large long-term gains; the newest lot is short-term).", "Earnings Report walkthrough: what one bad quarter does to this account.")),
            new SampleLesson("SAMPLE_TECH_HEAVY", "cpu", PortfolioStyle.ACTIVE,
                    "Most of the account in one sector, through a technology fund and single names — common after a long run for technology. The fund and the names overlap.",
                    List.of("Portfolio: the sector weights against the benchmark.", "Optimize: how the sector band pulls technology back toward the index.", "Attribution: allocation (being in technology) vs selection (which tech names).")),
            new SampleLesson("SAMPLE_INCOME", "banknote", PortfolioStyle.EITHER,
                    "Income first: mostly bond funds, plus dividend payers in utilities, staples, telecom, pipelines and REITs. These stocks behave a little like bonds — they fall when interest rates rise.",
                    List.of("Risk: interest-rate sensitivity shared by the bonds and the high-yield stocks.", "Yield Curve walkthrough: what rising long rates do to this account.", "Attribution against a 30/70 benchmark: the long-bond overweight.")),
            new SampleLesson("SAMPLE_CASH_HEAVY", "dollarsign.circle", PortfolioStyle.PASSIVE,
                    "Nearly half in cash, kept back after selling during a scary month and 'waiting for the dip'. Safe from a fall — but the plan says 60% stocks.",
                    List.of("Rebalance Bands: how far outside its band the stock weight is.", "Attribution: the cost of the cash in a rising year (allocation effect).", "Jobs Report walkthrough: the long-run cost of sitting out.")),
            new SampleLesson("SAMPLE_TRADER", "arrow.left.arrow.right", PortfolioStyle.ACTIVE, "trader",
                    "Positions changed every few weeks, chasing what moved. Every lot is under a year old, so gains are taxed as short-term income, and every trade pays a spread.",
                    List.of("Trades & Taxes: short-term gains (and losses) on almost every sale.", "Attribution: selection swings month to month.", "The Behaviour Lab result for 'react to every headline'.")),
            new SampleLesson("SAMPLE_DRIFTED_TRACKER", "scope", PortfolioStyle.PASSIVE,
                    "A hand-built index: the S&P 500's largest names, bought at their index weights two years ago and never rebalanced. Winners have grown, the other 478 names are missing, and it now tracks the index loosely.",
                    List.of("Tracking Error & Drift: how far the weights have moved from the index's.", "Build the Tracker: how many names it really takes, and the one-fund alternative.", "Attribution: almost no allocation, small selection — what passive should look like.")),
            new SampleLesson("SAMPLE_TAX_LOTS", "doc.text", PortfolioStyle.EITHER,
                    "The same security bought at different times and prices: some lots are large gains, some are losses. Which lot you sell — and whether you sell at a loss on purpose (tax-loss harvesting) — changes the tax bill for the same trade.",
                    List.of("Trades & Taxes: FIFO vs highest-cost-first lot choice.", "The bond fund lots at a loss: harvesting them offsets gains elsewhere.", "Wash-sale caution: buying the same security back within 30 days disallows the loss.")),
            new SampleLesson("SAMPLE_6040_DRIFT", "chart.pie", PortfolioStyle.PASSIVE,
                    "Set up as 60% stocks, 40% bonds; after a strong run for stocks the mix has drifted well past 60/40. Nothing is wrong with the funds — the account now takes more risk than planned.",
                    List.of("Rebalance Bands: the stock weight is outside a ±5-point band.", "Risk: volatility now vs at 60/40.", "In a retirement account rebalancing has no tax cost.")),
            new SampleLesson("SAMPLE_MISSING_DATA", "exclamationmark.triangle", PortfolioStyle.ACTIVE,
                    "Real account data is messy: a security that was delisted and never priced in the security master, a unit with no trades for weeks, and a newer listing with a short price history. The tools should say what they could not see.",
                    List.of("Portfolio: the errors and warnings under the holdings.", "Optimize: the unpriced holding is left out — the trade list doesn't touch it.", "Attribution: the data gap and the 'review' result.")));

    /** A lesson by code; null when none. */
    public static SampleLesson lesson(String code) {
        for (SampleLesson l : LESSONS) if (l.code().equals(code)) return l;
        return null;
    }

    /** A sample's name before its account is read: SAMPLE_TAX_LOTS → "Tax Lots". */
    public static String placeholderName(String code) {
        String s = code.replace("SAMPLE_", "").replace("_", " ");
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(start ? Character.toTitleCase(cp) : Character.toLowerCase(cp));
            start = Character.isWhitespace(cp);
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** My Portfolio before its account is read from the security master (a new copy each call). */
    public static Portfolio myPortfolio() {
        Portfolio p = new Portfolio("mine", "My Portfolio", AccountType.TAXABLE, "SP500", PortfolioStyle.EITHER, List.of(),
                "Your own account in the security master: real securities and prices, tax lots, and risk from the analytics service.",
                List.of("Add the securities you hold (My Holdings ▸ Add Securities…).", "Choose the benchmark that matches your plan."));
        p.symbol = "person.crop.circle";
        p.source = "srm";
        p.srmCode = "MY_PORTFOLIO";
        return p;
    }

    /** A sample account as the app's Portfolio, from its security-master account (holdings empty: then call apply). */
    public static Portfolio portfolio(SRMAccount a, SampleLesson l) {
        Portfolio p = new Portfolio(a.code(), a.name(), AccountType.fromCode(a.accountType()),
                a.benchmark() != null ? a.benchmark() : "SP500", l != null ? l.style() : PortfolioStyle.EITHER, List.of(),
                l != null ? l.lesson() : "", l != null ? l.lookFor() : List.of());
        p.trading = l != null ? l.trading() : "buyAndHold";
        p.symbol = l != null ? l.symbol() : "briefcase";
        p.source = "srm";
        p.srmCode = a.code();
        return p;
    }

    /** The empty placeholder of a sample before the security master answers (PortStore.init). */
    public static Portfolio placeholder(SampleLesson l) {
        Portfolio p = new Portfolio(l.code(), placeholderName(l.code()), AccountType.TAXABLE, "SP500", l.style(), List.of(),
                l.lesson(), l.lookFor());
        p.trading = l.trading();
        p.symbol = l.symbol();
        p.source = "srm";
        p.srmCode = l.code();
        return p;
    }

    /** Every sample's placeholder, in LESSONS order. */
    public static List<Portfolio> placeholders() {
        List<Portfolio> out = new ArrayList<>();
        for (SampleLesson l : LESSONS) out.add(placeholder(l));
        return out;
    }
}
