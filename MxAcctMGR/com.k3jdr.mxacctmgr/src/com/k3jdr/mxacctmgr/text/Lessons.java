package com.k3jdr.mxacctmgr.text;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every screen's page header, verbatim from the Swift app's {@code StepPage(subtitle:lessonTitle:lesson:)} calls: the
 * line under the page title (the subtitle), and the lesson card's title and points.
 *
 * <p>Keys are the Swift {@code Screen} raw values. Where the Swift picks the text by approach or condition, a suffixed
 * key holds the other variant:
 * <ul>
 * <li>{@code myPortfolio} – My Holdings, an account in the security master (the Swift's {@code isSRM} subtitle);
 *     {@code myPortfolio.local} – the subtitle for an account that is not in the security master. The holdings lesson
 *     is the same for active and passive.</li>
 * <li>{@code myBenchmark} – active approach; {@code myBenchmark.passive} – passive (the third point differs).</li>
 * <li>{@code myRisk} – Risk Today; {@code myRisk.pending} – the page shown while a security-master account's risk
 *     inputs come from the analytics service (SRMInputsPending: subtitle only, no lesson).</li>
 * <li>{@code myImprove} – Optimize (active); {@code myImprove.passive} – Build the Tracker (passive).</li>
 * <li>{@code myTrades} – Trades &amp; Taxes (active); {@code myTrades.passive} – Rebalance Bands (passive).</li>
 * <li>{@code myAttribution} – active; {@code myAttribution.passive} – passive (subtitle and lesson both differ).</li>
 * <li>{@code reportWalkthrough} – generic parts only: the subtitle is the report's headline and the first point names
 *     the report; use {@link #reportWalkthrough(String, String)}, or format the {@code %s} placeholders yourself.</li>
 * <li>{@code morningBriefing} – the Swift subtitle interpolates {@code Briefing.date}, a constant
 *     ({@link #BRIEFING_DATE}); it is filled in here.</li>
 * </ul>
 * {@link #of(String)} falls back from a suffixed key to its base key, so {@code of(screen + ".passive")} always works.
 * {@code securities} and {@code services} have no page header in the Swift app (split views, no StepPage): {@code of}
 * returns {@code null} for them and for unknown keys.
 */
public final class Lessons {

    /** A page header: the subtitle under the page title, the lesson card's title, and its points (may be empty). */
    public record Lesson(String subtitle, String title, List<String> points) {}

    /** The lesson card's title unless the Swift passes {@code lessonTitle:}. */
    public static final String DEFAULT_TITLE = "Why this step";

    /** {@code Briefing.date} in the Swift app: the illustrative morning of the Morning Briefing. */
    public static final String BRIEFING_DATE = "Wednesday 2026-10-21 (illustrative)";

    private static final Map<String, Lesson> ALL = new LinkedHashMap<>();

    private static void put(String key, String subtitle, String... points) {
        ALL.put(key, new Lesson(subtitle, DEFAULT_TITLE, List.of(points)));
    }

    private static void putTitled(String key, String subtitle, String title, String... points) {
        ALL.put(key, new Lesson(subtitle, title, List.of(points)));
    }

    static {
        // ---- Banking (BankViews, CardViews, ExpenseViews, SecureAccounts)
        put("checking",
                "The check book register, shared: everyone who writes checks enters them here, and the bank's own lines (PNC downloads) clear them.",
                "Enter each check, debit and deposit when it is written — the register is shared, so whoever writes the next check sees the balance you left.",
                "Download the account's activity from PNC Online Banking (Quicken .qfx, or CSV) into the drop folder: the daily refresh — or Check PNC Downloads here — matches the bank's lines to your entries by amount and check number and marks them cleared.",
                "The bank's lines left over are entries the register doesn't have (fees, card debits, automatic payments): accept each into the register, or match it to the entry it is. Register, cleared and bank balances agree when the account is reconciled.");
        put("cards",
                "Each card's register: what was charged and paid, the balance owed and the credit left, statement by statement. The card company's downloads post to it.",
                "Download the card's activity — Chase: Spreadsheet (CSV) or Quicken (QFX); Discover and Capital One: CSV — and Import File… it here: what it holds for the card shows first. Downloads that overlap are fine: each line is read once.",
                "Each line posts to the register with its target (found by the name the card company uses, once you have picked a target for it) and category (the target's, else the card company's). Enter a charge by hand from a receipt and the download's line matches it.",
                "Pay… writes the checking entry and the card's payment together, linked; a payment already in the check register links by itself (same amount within a week). A statement is paid when the payments by its due date cover its balance.");
        put("expenseLedgers",
                "Ledgers of what is bought for a purpose — equipment and software, a home office, subscriptions, training, travel — each item on a budget line, and the transfers that fund it.",
                "Enter each item on its budget line (Mac & Software, Home Office Desk Buildout, Subscriptions …): the line shows what was spent against its budget. A monthly or yearly item is a subscription: its yearly cost and next renewal show on Subscriptions, and Renew records the next charge.",
                "Transfers from the funding account fund the ledger: the balance is what was spent and not funded yet (green when funded ahead). Fund… plans a monthly transfer; the ones still to come are scheduled and count toward the projected balance only. A transfer in the check register links to its entry by itself.",
                "What the money buys is kept too: equipment as assets (where it is, who has it, its depreciation and warranty), training (hours, completion, certificates) and trips (dates and per-diem allowances against what was spent). An entry can wait for approval by someone other than who entered it.");
        put("expenses",
                "Where the registers' money goes to or comes from — a utility, a card, a brokerage, a person: their targets, not books MxAcctMGR keeps.",
                "Each target account has its bank or company and its last four digits, so picking a register entry's target shows which account the money went to — and a note, shown on hover in the register and edited there. Its funding sources say which registers offer it: recipients of checking account payments, of credit card payments, or both.",
                "Each is in a category (Utilities, Insurance, Housing …): the registers file every entry under its target's category, so a year's spending adds up by category. Its usual amount fills in the register; its next payment, nickname, identifier, logon and address are kept with it (New Target Account…). Its password and user keys stay in this PC's secure storage, never in the security master.",
                "PNC's downloads name them their own way (\"DUKE ENERGY PAYMENT\"): accepting a bank line under a target account remembers that name, so the next one is filed to it.");
        put("secureAccounts",
                "The products and services you hold an account with — software, data services, APIs — with their service, your logon and the subscription's payment and renewal.",
                "Each secure account is a product: its name and description, its service's web address and API, and whether the service is active. Your user name is kept with it; its product key, service key and password stay in this PC's secure storage, never in the security master.",
                "Its subscription has the last payment (date and amount) and the renewal: when, how much, and whether it renews on its own, you renew it by hand, or you decline it and let it lapse — in Edit Service Details.",
                "The service key is also the API key: MxJobsMGR's API subscriptions each name a secure account and send its service key, so this is the one place API keys are entered and changed.");

        // ---- Start Here and Learn the Why (LearnViews)
        putTitled("welcome",
                "A portfolio-management IDE that explains why before it shows how.",
                "Before you start",
                "MxAcctMGR teaches how professional portfolio tools — optimization, risk, index tracking and attribution — apply to personal accounts, and what everyday decisions cost over the long run.",
                "Everything runs on real data from the security master: real securities, prices and benchmarks, sample accounts built from real purchases, and risk estimated from real daily prices. The market-report walkthroughs are scenarios in the shape of real releases, and the futures are simulations.",
                "It is educational. It never tells you what to buy or sell for your own accounts; talk to a licensed adviser about your situation.");
        put("choosePath",
                "Two ways to run a portfolio. The questions help you see the trade-offs; the choice is yours.",
                "An active portfolio tries to beat a benchmark by holding different weights from it — overweighting what you expect to do well. It takes active risk to do so, measured by tracking error.",
                "A passive portfolio tries to match an index as closely and cheaply as possible, and to stay matched as prices move.",
                "Many people combine them: a passive core with a small active satellite. The samples include both; copy one into My Portfolio to try it.");
        put("whyOptimize",
                "Good picks are not a portfolio. How much of each — and how they move together — decides the risk you take.",
                "Every holding has risk of its own and risk it shares with the market. Holding more companies diversifies away their own risk — for free — but not the market's.",
                "More return usually needs more risk: the stock / bond mix sets most of an account's risk and long-run return.",
                "An optimizer turns three things into weights: forecasts (what you expect), a risk model (how things move together) and limits (what you won't do). It is only as good as its forecasts, which is why the limits matter.");
        put("whyAttribution",
                "A return on its own says little. Attribution says where it came from — so you can tell skill from luck, and a working plan from a broken one.",
                "Active return is the account's return minus the benchmark's. It comes from two decisions: how much to put in each sector (allocation) and which companies to hold in it (selection).",
                "Allocation: overweighting a sector that beat the whole benchmark adds return. Selection: your companies in a sector beating the benchmark's companies in that sector adds return. Interaction: overweighting where your selection was good.",
                "For an active account, attribution tests the story: did the bets you meant to make pay, or did something you didn't mean to do (a sector drift) drive the result? For a passive account, every effect should be close to zero.");
        put("activeVsPassive",
                "What each approach costs and promises, before you commit to one.",
                "Passive: the market's return minus a small fee, with almost no tracking error. You give up the chance to beat the market in exchange for near-certainty of not trailing it by much.",
                "Active: the chance to beat the benchmark, paid for with higher fees, trading costs, taxes in a taxable account, and tracking error — years well behind as well as ahead.",
                "In many published studies most actively managed funds trail their index after fees over 10–15 years — often 80–90% of them. An active plan should say why it expects to be in the rest, and attribution should check every year.");

        // ---- Investments (PathViews, PassiveViews)
        String[] holdings = {
                "Start with what you actually own: each position, what it is worth today and what you paid for it (tax lots).",
                "Look through funds to what they hold — an index fund plus a few single stocks may double up on the same names.",
                "Fix the data first. A holding without a price can't be valued, tracked or rebalanced reliably.",
        };
        put("myPortfolio",
                "What the account holds, from the security master: real securities, prices and tax lots.",
                holdings);
        put("myPortfolio.local",
                "What the account holds: positions, values and tax lots.",
                holdings);
        String benchmarkSubtitle = "The portfolio the account is measured against — choose the one that matches your plan.";
        String benchmark1 = "A benchmark is the portfolio you would hold with no opinions; every difference from it is a bet — and a risk.";
        String benchmark2 = "Choose one that matches the plan: an all-stock index for an all-stock account; a blend for an account that also holds bonds and cash. Measured against the wrong one, sensible holdings look like big bets.";
        put("myBenchmark", benchmarkSubtitle,
                benchmark1, benchmark2,
                "For an active account the benchmark is the starting point the optimizer departs from within a risk budget.");
        put("myBenchmark.passive", benchmarkSubtitle,
                benchmark1, benchmark2,
                "For a passive account the benchmark is the index to follow; the next steps measure and close the gap.");
        put("myRisk",
                "How much the account can move, where that risk comes from, and how far it sits from the benchmark.",
                "Before changing anything, measure: an optimizer moves risk around, so you need to know where it is now.",
                "Risk is not shared out like weight. A volatile or concentrated holding can be a small part of the value and a large part of the risk — the contribution chart shows both.",
                "Tracking error is the risk of being different from the benchmark. It is what an active manager spends to try to beat it, and what a passive manager tries to keep near zero.");
        put("myRisk.pending",
                "Risk inputs for your account come from the analytics service: a covariance estimated from the security master's prices.");
        put("myImprove",
                "Turn forecasts into position sizes, inside limits you choose.",
                "The optimizer maximises forecast active return minus a penalty for active risk, starting from the benchmark. Here the forecasts are the analytics service's momentum alpha for each company (12-1 month price momentum, scaled by an information coefficient) — a simple, transparent stand-in for the view a manager or a research service provides.",
                "The tracking-error target is your risk budget: how far from the benchmark you are willing to be. The weight, active-weight and sector limits stop the optimizer from betting everything on its favourite forecast.",
                "Read the result as a conversation: which limits bind tell you what is holding the portfolio back; the decision says whether the improvement is worth the trading.");
        put("myImprove.passive",
                "The fewest names that follow the index closely enough — or one fund that does it for you.",
                "Full replication holds every name at index weight: no tracking error from missing names, but many small positions.",
                "Sampling holds fewer names, chosen and weighted to keep tracking error small. Stratified sampling spreads them across sectors so no sector is missing; largest-weight sampling holds the biggest names.",
                "The curve shows diminishing returns: the first names cut tracking error fast, the last ones barely. For most personal accounts one low-cost index fund does all of this — the comparison below shows what each choice costs, including taxes on switching.");
        put("myTrades",
                "What it takes to get there: the trades, their cost, and the taxes they trigger.",
                "Every trade has a cost: the bid–ask spread and any commission (0.15% of the amount here). Turnover is the share of the account traded.",
                "In a taxable account selling realises gains. Long-held lots are taxed at the lower long-term rate; lots held a year or less at the higher short-term rate. Which lot you sell changes the bill for the same trade.",
                "You don't have to go all the way. Moving part of the way captures much of the improvement for less turnover and tax — the slider below shows how much.");
        put("myTrades.passive",
                "When to trade back to the plan's mix — by rule, not by mood.",
                "The stock / bond / cash mix sets most of the account's risk. As prices move it drifts: after a rally stocks become a larger share, so the account takes more risk than planned.",
                "A rebalance band is the rule: trade back only when a weight leaves target ± band. Calendar rebalancing (every year) is the other common rule; bands trade only when needed.",
                "Wider bands mean fewer trades, lower costs and taxes, but more drift. In a taxable account rebalance with new deposits and dividends first, and in tax-deferred accounts where possible.");
        put("myAttribution",
                "Where last year's difference from the benchmark came from.",
                "Attribution splits the active return into allocation (sector weights), selection (companies within sectors) and interaction, month by month, linked over the year.",
                "Compare it with the plan: if the plan was 'pick better companies' but allocation dominates, the result came from sector bets you may not have meant to make.",
                "One year is a small sample. A good process can have a bad year; attribution over several years is what separates skill from luck.");
        put("myAttribution.passive",
                "Did the account do what an index portfolio should — follow the index?",
                "For a passive account attribution is a check-up: allocation and selection should both be close to zero. A large effect means drift (a sector grew away from the index) or a mistake (a missing name, idle cash).",
                "Cash shows as allocation: in a rising year, money not invested costs return.",
                "Small but steady negative selection is often fees and sampling: the price of holding fewer names than the index.");

        // ---- Knowledge Library (ReportsHubViews, EarningsViews, VIX, Compute, ReportView)
        put("reportLibrary",
                "The reports portfolio managers watch — some move markets in seconds, others shape positioning over weeks or months.",
                "Asset prices rest on four things: economic growth, inflation, interest rates and corporate profits. Every report here is news about one or more of them; positioning reports add how investors are already placed.",
                "Professionals watch them because their mandates and horizons are short. For a long-term personal plan they explain why prices moved — they are rarely a reason to change the plan.",
                "Each report has a seven-step walkthrough: read it, see the market's move on your account, choose a reaction, and simulate the long run against staying with the plan.");
        putTitled("morningBriefing",
                BRIEFING_DATE + ": the ten things many institutional PMs review before the open.",
                "How to read a morning briefing",
                "A briefing answers three questions: what happened overnight, what is scheduled today, and how investors are positioned and feeling.",
                "Professionals use it to manage risk around events in the next hours and days. For a long-term account, it is context — what you'll read about today — not a to-do list.",
                "The market panels are an illustrative morning in the shape of a real one; your account and today's earnings list are real (security master, Nasdaq.com and FMP). Point at a panel for what it measures and how a long-term investor might use it.");
        putTitled("earningsCalendar",
                "Real earnings reports by month or week: past days with how many companies beat or missed, upcoming days with who reports and what analysts expect. Select a day for its full list.",
                "Reading an earnings season",
                "Earnings seasons peak three to six weeks after each quarter ends: from mid-January, mid-April, mid-July and mid-October into the following month. The large banks report first; the busiest days list several hundred companies.",
                "Past days show how many companies beat or missed the consensus EPS (green, red); upcoming days show the largest companies to report and, in the week view, what analysts expect.",
                "The share that beats says less than by how much, and what companies say about the next quarters. Select a day for every company, its revenue and its history.");
        putTitled("earningsDay",
                "Every company reporting on a day: U.S.-listed companies from Nasdaq.com's earnings calendar, with revenue from FMP. Real data.",
                "How to read an earnings list",
                "Companies report each quarter, most of them in four earnings seasons that begin about two weeks after a quarter ends. Before the open (around 7–8 a.m. Eastern) or after the close (around 4–5 p.m.): either way the stock reacts at the next trading session.",
                "The consensus is the average analyst forecast, and prices already reflect it. The market reacts to the surprise — and often more to what the company says about the coming quarters (guidance) than to the quarter just reported.",
                "In recent years roughly three in four large U.S. companies have beaten the EPS consensus in a typical quarter, partly because companies steer analysts toward numbers they can beat. A beat is ordinary; a miss stands out.",
                "Through an index fund each of these companies is a small part of an account. For a long-term plan, a single report is rarely a reason to trade.");
        put("vix",
                "The Cboe Volatility Index — the market's \"fear gauge\" — with real data from Cboe.",
                "The VIX is the volatility the market expects for the S&P 500 over the next 30 days, worked out from S&P 500 option prices. It is quoted as an annual percentage: 20 means about a 5.8% move up or down in a typical month, about 1.3% in a day.",
                "It jumps when stocks fall fast and then drifts back. Every spike above 35 since 1990 came back under 20 — some within weeks, some only after a year or more.",
                "The highest readings came close to market lows, when selling felt most urgent. For a long-term plan the VIX explains why prices are swinging; it isn't a signal to change the plan.");
        put("compute",
                "AI compute as an asset theme, followed through what the hyperscalers spend to build it — real reported figures.",
                "Compute — data centres, AI chips, memory, networking, power — is being built mostly by five companies: Microsoft, Alphabet, Amazon, Meta and Oracle. Their capital expenditure (capex) is the best public measure of how fast it is growing.",
                "Their capex is their suppliers' revenue: when the hyperscalers spend more, chip, memory, networking and power companies sell more. Watching capex, and what the hyperscalers say about it on their earnings calls, is how professionals follow the theme.",
                "Building ahead of demand has happened before: telecom companies laid far more fibre around 2000 than was used for years, and their suppliers' shares fell 80–90%. Spending rising faster than revenue, and depreciation catching up, are what to watch.",
                "An S&P 500 index fund already holds these companies, and in large weights. Check how much of your account is in the theme before adding more.");
        // Subtitle %s = the report's headline (ReportCatalog.Report.headline); first point %s = the report's title.
        // The page title is the report's title, not the screen's.
        putTitled("reportWalkthrough",
                "%s",
                "What this walkthrough shows",
                "How to read the %s — what each number means and what the market watches.",
                "What the day's move does to the account selected in the toolbar, holding by holding.",
                "Then you choose a reaction and the Behaviour Lab simulates it against staying with the plan over many possible futures: missed recovery days, trading costs, taxes and tracking error.");
        put("samples",
                "Accounts built to show the cases real portfolios run into. Use one in the report walkthroughs, or copy it into My Portfolio, to learn to read the results.",
                "Each sample is one edge case: what a tool should flag, and what its results look like when something is unusual.",
                "Use a sample in the report walkthroughs — the same news lands very differently on a concentrated, an income and a cash-heavy account — or copy it into My Portfolio to work through its steps.",
                "Every sample is an account in the security master, built from real securities bought on real dates at their real closes (srm.py load sample-portfolios). My Portfolio is yours to edit, kept there too.");
        put("spending",
                "Household spending attribution: like portfolio attribution, for the family's spending — why it changed from last year (prices, more of the same, the children's ages, habits, one-time events) and what the children's ages bring in the next ten years.",
                "Every dollar the check register, the credit cards and the expense ledgers spend has two dimensions: a spending category (Food, Housing, Transportation, Education, Activities, Technology, Healthcare, Clothing, Travel) and a primary driver (inflation, family growth, a new activity, a lifecycle transition, a preference change, a one-time event). Rules on expense and target accounts set them; tag a single entry (Philmont as a one-time event) on Entries.",
                "Each category's change splits as in performance attribution: change = inflation + usage + lifecycle + behavior + one-time + taxes. Inflation is last year's spending times the category's CPI change (FRED, in the security master); each driver's effect is what its spending grew beyond inflation. Inflation and lifecycle are the unavoidable part; behavior is habit.",
                "The forward curve stacks each child's lifecycle costs — club sports at 10, a phone at 12, a laptop at 13, tutors at 14, driving at 16, college visits and applications at 16–17, college at 18 — so the waves show three to five years ahead.");
    }

    private static final Map<String, Lesson> VIEW = Collections.unmodifiableMap(ALL);

    private Lessons() {}

    /**
     * The header for a screen key (see the class comment). A suffixed key that has no variant of its own falls back to
     * its base key ({@code "myPortfolio.passive"} gives {@code "myPortfolio"}); {@code null} for {@code securities},
     * {@code services} and unknown keys.
     */
    public static Lesson of(String key) {
        if (key == null) return null;
        Lesson lesson = VIEW.get(key);
        int dot = key.indexOf('.');
        if (lesson == null && dot > 0) lesson = VIEW.get(key.substring(0, dot));
        return lesson;
    }

    /** Every key with a header of its own (Banking, Start Here and Learn the Why, Investments, Knowledge Library). */
    public static Set<String> keys() {
        return VIEW.keySet();
    }

    /** A report walkthrough's header: the report's headline as subtitle, its title in the first point. */
    public static Lesson reportWalkthrough(String reportTitle, String headline) {
        Lesson generic = VIEW.get("reportWalkthrough");
        List<String> points = generic.points().stream()
                .map(p -> p.contains("%s") ? String.format(p, reportTitle) : p)
                .toList();
        return new Lesson(headline, generic.title(), points);
    }
}
