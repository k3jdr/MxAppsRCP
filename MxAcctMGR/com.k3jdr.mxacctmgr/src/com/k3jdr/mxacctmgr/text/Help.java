package com.k3jdr.mxacctmgr.text;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What Quick Help and the tooltips say, verbatim from the Swift app: {@code PortQuickHelp.swift} (each screen, the
 * account selected, the disclaimer, the account and engine pickers, the glossary terms) and the frame's help texts in
 * {@code ContentView.swift}, {@code Navigation.swift} and {@code Components.swift}.
 *
 * <p>Naming: {@code X_TITLE} and {@code X} are a Quick Help item's title and detail; {@code X_HELP} is a tooltip
 * ({@code .help(...)}); {@code X_SHORTCUT} is the shortcut Quick Help shows. A {@code _FORMAT} constant takes
 * {@link String#format} arguments, documented on it.
 */
public final class Help {

    private Help() {}

    // ------------------------------------------------------------------ PortQuickHelp.swift: the screens

    /**
     * The Quick Help detail for a sidebar screen, by its Swift {@code Screen} raw value ({@code null} if unknown). Its
     * Quick Help title is the screen's full title.
     */
    public static String screen(String key) {
        if (key == null) return null;
        return switch (key) {
            case "checking" -> "The check book register, shared: everyone who writes checks on the account enters them here, kept in the security master. The bank's own lines (PNC downloads) are matched to the entries and mark them cleared; the ones left over are the bank's entries to accept.";
            case "cards" -> "Each credit card kept as a register in the security master: charges, credits, payments, fees and interest with the balance owed and the credit left, and its statements — balance, minimum due, due date, paid or not. The card company's downloads (Chase CSV / QFX, Discover and Capital One CSV) post to it, each line once; its payments link to the checking entries that paid them.";
            case "expenseLedgers" -> "Funded ledgers of purpose spending — equipment and software, a home office, subscriptions, training, travel — kept in the security master: each item on a budget line with its budget, the transfers that fund it and the balance not funded yet. Equipment is tracked as assets (depreciation, warranty), training as records (hours, certificates), trips against their per-diem allowances, and subscriptions by what they cost a year and when they renew. Entries can wait for approval.";
            case "expenses" -> "The target accounts your registers pay or are paid by — a utility, a card, a brokerage, a person — with the bank or company, the last four digits, a category, a note, a nickname, status, identifier, next payment, logon and address, and the registers that pay them (funding sources: checking account payments, credit card payments), which decide where each is offered as a target. The password and user keys stay in this PC's secure storage. They are the registers' targets: not books MxAcctMGR keeps.";
            case "secureAccounts" -> "The products and services you hold an account with — software, data services, APIs — with the service's address and API, its status, your user name, and the subscription's last payment and renewal (auto-renew, renew by hand, or decline). The product key, service key and password stay in this PC's secure storage (Keys lists which are stored); the service key is the API key MxJobsMGR's API subscriptions send for the account. Right-click a row to rename it or edit its details.";
            case "welcome" -> "What MxAcctMGR teaches, how the guide is laid out, and how Quick Help works. Everything runs on real data from the security master (accounts, securities, prices, benchmarks) and the analytics service (risk); the report walkthroughs are scenarios in the shape of real releases.";
            case "choosePath" -> "The questions that separate an actively managed portfolio from a passive index one, and a start button for My Portfolio. It doesn't choose for you.";
            case "whyOptimize" -> "Why a portfolio needs more than good picks: diversification, the risk–return trade-off, and what an optimizer does with forecasts, a risk model and limits. Two live demos.";
            case "whyAttribution" -> "Why results need explaining: was it being in the right sectors (allocation) or picking the right companies (selection)? A two-sector demo you can change.";
            case "activeVsPassive" -> "Costs, tracking error and the odds of beating an index: what you sign up for with each approach. A fee-compounding demo.";
            case "myPortfolio" -> "Investments, step 1: your own holdings in the security master (srm) — with shares, values and tax lots; add, edit and remove them, and fix the checks first. Ctrl+K links a holding's ticker or name to a page, with hover text of its own.";
            case "myBenchmark" -> "Investments, step 2: the benchmark that matches your plan — an index loaded in the security master (S&P 500, Dow, Nasdaq-100, or the VTI / BND 60/40 and 30/70 blends) — with sector and asset-mix comparisons, overlap, active share and its largest members.";
            case "myRisk" -> "Investments, step 3: how much your account can move, where its risk comes from, and how far it sits from your benchmark.";
            case "myImprove" -> "Investments, step 4: with an active approach, optimize within a risk budget (built-in or portfolio-java); with a passive approach, build the tracker with the fewest names or compare one index fund (built-in or portfolio-cpp).";
            case "myTrades" -> "Investments, step 5: active — the trades, their cost and the taxes by lot; passive — the stock / bond / cash mix against its rebalance bands and the trades to return.";
            case "myAttribution" -> "Investments, step 6: last year's difference from your benchmark split into allocation, selection and interaction by sector, month by month.";
            case "reportLibrary" -> "The reports portfolio managers watch, in six tiers — market-moving economic reports, growth, corporate, positioning, fixed income and industry — with what each measures, why PMs care, the price drivers it moves, and the five with the greatest impact. Open any one's walkthrough, or its real report at the publisher (BLS, the Fed, BEA, ISM, Census…).";
            case "morningBriefing" -> "A morning briefing: overnight markets, Treasury yields, the economic calendar, the economic surprise index, Fed funds futures, fund flows, credit spreads and the VIX as an illustrative morning; today's real earnings list; and what the account would be worth if it opened with the futures.";
            case "earningsCalendar" -> "Real earnings reports by month or week: for past days how many U.S.-listed companies beat or missed the consensus EPS, for upcoming days who reports and what analysts expect, with the companies you hold marked. Select a day for its full list. Nasdaq.com's calendar, with revenue from FMP through the accounts service.";
            case "earningsDay" -> "Every company reporting on a day — symbol, company, before the open or after the close, consensus and actual EPS with the surprise (Nasdaq.com), revenue estimate and actual (FMP) — sortable and filterable, with the selected company's history. Ctrl+K links a company's symbol or name to a page, with hover text of its own. Real data.";
            case "vix" -> "The Cboe Volatility Index, real data from Cboe: today's close, its regime and percentile since 1990, its history with calm-to-panic bands, the term structure (9-day to 6-month), and every spike above 35 with how long the VIX took to come back under 20.";
            case "compute" -> "AI compute as an asset theme, followed through the hyperscalers' capex (Microsoft, Alphabet, Amazon, Meta, Oracle): quarterly and combined, its growth, its share of their revenue and cash flow, and depreciation — beside their suppliers' revenue (Nvidia, Broadcom, AMD, Micron, Arista, Vertiv, Equinix) and how much of My Portfolio is in the theme. Reported figures from FMP through the accounts service.";
            case "reportWalkthrough" -> "A report walkthrough in seven steps: the report, how to read it, the market's move, what it did to the account, your reaction, its long-run consequences against the plan, and the takeaways. Choose another report with the Report menu at the top. Its first step links to the real report: the publisher's latest release, release schedule and history.";
            case "spending" -> "Household spending attribution, like portfolio attribution for the family's spending. Every dollar the check register, the credit cards and the expense ledgers spend gets a spending category (Food, Housing, Transportation, Education, Activities, Technology, Healthcare, Clothing, Travel) and a primary driver (inflation, family growth, a new activity, a lifecycle transition, a preference change, lifestyle and hobbies, a one-time event, taxes) from rules on expense accounts, target accounts and statement text (for card charges with no target account) and tags on single entries. Each category's change from last year splits into inflation (CPI from FRED in the security master, else an assumption), usage, lifecycle, behavior, one-time and taxes effects; the key metrics table shows which increases are unavoidable and which are habit. Right-click a category's bar or name (or click its name in the table) for a Deep Dive: its months, drivers, the rules and tags that put entries in it, the accounts and payees behind the change, its largest entries, members, lifecycle triggers and entries. The forward curve stacks the children's lifecycle costs ten years ahead from their birth years. The strip above the tabs names the sources and how fresh they are; Sources chooses the checking accounts, cards and ledgers that feed it and shows each one's folders, downloads, tables, last import and the time window, with every SRM table the analysis reads and its newest data. Admin explains how driver and category relate, with a Category × Driver table whose cells show what put their entries there, and how target accounts and expense ledgers relate, with the ledger entries that match an unlinked card charge (counted twice) and Link. Year-End Tax lists a tax year's tax-applicable expenses with their deductible share, estimated and local tax payments by quarter, real estate taxes, refunds and the tax savings account, from the tax items; Export CSV… saves it for whoever prepares the return. Feedback holds the notes the family sends from the page's read-only web version on the home network, with your status and reply, which their page shows back. Kept in the security master (V037–V045) through the accounts service.";
            case "securities" -> "The security master's securities, as on MxDataHUB's Securities page: search by name or any identifier (ticker, ISIN, CUSIP, SEDOL, FIGI, CIK, LEI), filter by type, benchmark and activity or by any column's header, and see the selected one's prices, identifiers, issuer and security facts, listings, classification, benchmark membership and corporate actions. Through the accounts service.";
            case "samples" -> "Every sample account, the edge case it teaches and what to look for. Use one in the report walkthroughs and the Morning Briefing; copy one into My Portfolio to work through its steps.";
            case "services" -> "The shared Layer 3 services (MxAppsIOS/Services) — portfolio-java for active optimization, portfolio-cpp for tracking and risk, analytics for attribution — whether they run and how to start them.";
            default -> null;
        };
    }

    /**
     * Quick Help for the account selected (PortHelp.selection). Title: the portfolio's name. Detail format arguments:
     * the account type (e.g. "Taxable"), its total value as money, the benchmark's name, the account's lesson.
     */
    public static final String SELECTION_FORMAT = "%s, %s, benchmark %s. %s";

    // ------------------------------------------------------------------ PortQuickHelp.swift: fixed items

    public static final String DISCLAIMER_TITLE = "Educational Use Only";
    public static final String DISCLAIMER = "The accounts, securities and prices in MxAcctMGR are real, from the security master; the report walkthroughs are scenarios in the shape of real releases and the futures are simulations, to show how the tools work and what choices cost. Nothing here is a recommendation for your own accounts.";

    /** The toolbar's account picker. */
    public static final String SAMPLE_PICKER_TITLE = "Account";
    public static final String SAMPLE_PICKER = "The account the report walkthroughs and the Morning Briefing work on (the Investments steps always use My Portfolio). Each sample is an edge case — concentrated, sector-heavy, income, cash-heavy, frequent trading, a drifted tracker, tax lots, a band breach, messy data — or My Portfolio, which you can edit.";

    /** The toolbar's engine picker. */
    public static final String ENGINE_TITLE = "Engine";
    public static final String ENGINE = "Layer 3 services, the default, sends the same inputs to portfolio-java (active, port 8766) and portfolio-cpp (tracking, port 8767), the engines MxJobsMGR and MxDataHUB use, so you can compare. Built-in runs the optimizers inside MxAcctMGR, with no services. Start the services with python Services\\mxservices.py start.";

    // ------------------------------------------------------------------ PortQuickHelp.swift: glossary terms

    /** Glossary terms (Quick Help and tooltip of a term), in the Swift source's order. Unmodifiable. */
    public static final Map<String, String> TERMS;

    static {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("Tracking error", "How much the account's return is expected to differ from the benchmark's in a typical year (standard deviation of the difference). 1% is index-like; 3–6% is a typical active fund.");
        t.put("Active weight", "Account weight minus benchmark weight. Positive: overweight (a bet it does better); negative: underweight.");
        t.put("Active share", "Half the sum of the absolute active weights: the share of the account that differs from the benchmark. 0% is the index; above 60% is very active.");
        t.put("Information ratio", "Forecast active return divided by tracking error — reward per unit of active risk. Above 0.5 is good for a forecast; realised ratios are usually lower.");
        t.put("Beta", "How much the account tends to move when the benchmark moves 1%. Below 1 is more defensive; above 1 more aggressive.");
        t.put("Volatility", "The typical size of a year's ups and downs (standard deviation of annual return).");
        t.put("VaR (95%, 1 month)", "A month's loss the account should not exceed 19 months in 20 under the risk model. Not the worst case.");
        t.put("Expected shortfall", "The average loss in the worst 1 month in 20: what a bad month looks like when it comes.");
        t.put("Allocation effect", "Return from weighting sectors differently from the benchmark: overweight a sector that beat the benchmark → positive.");
        t.put("Selection effect", "Return from holding different companies within a sector: your sector holdings beat the benchmark's in that sector → positive.");
        t.put("Interaction effect", "Return from overweighting the sectors where your selection also worked (or underweighting where it didn't).");
        t.put("Turnover", "Half the sum of absolute weight changes: the share of the account bought (and sold) to reach the target.");
        t.put("Rebalance band", "How far a weight may drift from its target before you trade back. Wider bands trade less (cheaper, fewer taxes) but allow more drift.");
        t.put("Look-through", "A fund replaced by what it holds, so an index fund plus a single stock shows the stock's true total weight.");
        t.put("Tax lot", "Shares bought at one time and price. Selling a specific lot decides the gain: highest-cost-first usually realises the least gain.");
        t.put("Carino linking", "Monthly effects scaled so that, over a year, they add up exactly to the compounded active return.");
        TERMS = Collections.unmodifiableMap(t);
    }

    /** A term's definition; as in the Swift {@code .term(t)}, the tooltip falls back to the term itself. */
    public static String term(String t) {
        return TERMS.getOrDefault(t, t);
    }

    // ------------------------------------------------------------------ ContentView.swift: the frame

    /** The account picker's tooltip on the Investments screens (where it is disabled). */
    public static final String ACCOUNT_PICKER_HELP_INVESTMENTS = "The Investments steps always use My Portfolio, your own account";
    /** The account picker's tooltip elsewhere. */
    public static final String ACCOUNT_PICKER_HELP = "The account the report walkthroughs and the Morning Briefing work on";
    public static final String ENGINE_PICKER_HELP = "Run the optimizers built-in or through the Layer 3 services (portfolio-java, portfolio-cpp)";

    /** The data-problem strip's buttons (above the screen when the accounts can't load). */
    public static final String TRY_AGAIN = "Try Again";
    public static final String LAYER3_SERVICES_BUTTON = "Layer 3 Services…";

    /** Leaving a portfolio with unsaved changes. Title argument: the portfolio's srm code. */
    public static final String SAVE_CHANGES_TITLE_FORMAT = "Save changes to %s?";
    /** Arguments: the action (e.g. "open another portfolio"), then the unsaved changes as lines "• change" joined by "\n". */
    public static final String SAVE_CHANGES_MESSAGE_FORMAT = "Before you %s, these changes are not saved:\n%s";
    public static final String SAVE_CHANGES_BULLET = "• ";
    public static final String SAVE = "Save";
    public static final String DISCARD_CHANGES = "Discard Changes";
    public static final String CANCEL = "Cancel";

    /** Save refused because the portfolio changed elsewhere. Title argument: the srm code. */
    public static final String SAVE_CONFLICT_TITLE_FORMAT = "Not saved: %s changed elsewhere";
    /** Argument: the service's conflict message. */
    public static final String SAVE_CONFLICT_MESSAGE_FORMAT = "%s\n\nYour edits are still here. Revert reads the saved version (yours are lost); or keep editing and copy what you need first.";
    public static final String KEEP_EDITING = "Keep Editing";
    public static final String REVERT_TO_SAVED = "Revert to the Saved Version";

    /** The error alert's title and buttons (Layer 3 Services… shows when the engine is the services). */
    public static final String ERROR_TITLE = "MxAcctMGR";
    public static final String OK = "OK";

    /** The status bar. */
    public static final String STATUS_TITLE = "Status";
    public static final String STATUS = "The account, its type and value and its benchmark; the last action's message; and the reminder that everything here is educational.";
    /** The status bar's account line. Arguments: portfolio name, account type, total value as money, benchmark code. */
    public static final String STATUS_LINE_FORMAT = "%s — %s, %s vs %s";

    // ------------------------------------------------------------------ Navigation.swift: Back and Forward

    public static final String BACK_TITLE = "Back";
    public static final String BACK = "Back to the place before, as in a browser: the screen, walkthrough or earnings day you came from. Hold the button for the places before it; Go ▸ History lists them too. Also Ctrl+Left, or a mouse's back button.";
    public static final String BACK_SHORTCUT = "Ctrl+[";
    /** Tooltip when there is a place to go back to. Argument: that place's title. */
    public static final String BACK_HELP_FORMAT = "Back to %s (Ctrl+[ or Ctrl+Left) — hold for the places before";
    /** Tooltip with nowhere to go back to. */
    public static final String BACK_HELP = "Back (Ctrl+[ or Ctrl+Left)";

    public static final String FORWARD_TITLE = "Forward";
    public static final String FORWARD = "Forward again to where you went Back from. Hold the button for the places after it. Also Ctrl+Right, or a mouse's forward button.";
    public static final String FORWARD_SHORTCUT = "Ctrl+]";
    /** Tooltip when there is a place to go forward to. Argument: that place's title. */
    public static final String FORWARD_HELP_FORMAT = "Forward to %s (Ctrl+] or Ctrl+Right) — hold for the places after";
    /** Tooltip with nowhere to go forward to. */
    public static final String FORWARD_HELP = "Forward (Ctrl+] or Ctrl+Right)";

    /** The Go menu: Back (Ctrl+[), Forward (Ctrl+]), History ▸, then Previous Step (Ctrl+Alt+Up), Next Step (Ctrl+Alt+Down). */
    public static final String GO_MENU = "Go";
    public static final String GO_HISTORY = "History";
    public static final String GO_PREVIOUS_STEP = "Previous Step";
    public static final String GO_NEXT_STEP = "Next Step";
    /** A history entry for Earnings by Day. Arguments: the screen's title, the day (e.g. "Tue, Oct 20, 2026"). */
    public static final String HISTORY_DAY_FORMAT = "%s — %s";

    // ------------------------------------------------------------------ Components.swift: StepFooter

    /** The Previous button's tooltip. Argument: the previous screen's full title. */
    public static final String PREVIOUS_STEP_HELP_FORMAT = "Previous step: %s (Ctrl+Alt+Up)";
    public static final String PREVIOUS_STEP_TITLE = "Previous Step";
    /** Argument: the previous screen's full title. */
    public static final String PREVIOUS_STEP_FORMAT = "Back to %s, the step before this one in the guide. (Ctrl+[ goes back to where you were, as in a browser.)";
    public static final String PREVIOUS_STEP_SHORTCUT = "Ctrl+Alt+Up";

    /** The Next button's label. Argument: the next screen's title. */
    public static final String NEXT_STEP_LABEL_FORMAT = "Next: %s";
    /** The Next button's tooltip. Argument: the next screen's full title. */
    public static final String NEXT_STEP_HELP_FORMAT = "Next step: %s (Ctrl+Alt+Down)";
    public static final String NEXT_STEP_TITLE = "Next Step";
    /** Argument: the next screen's full title. */
    public static final String NEXT_STEP_FORMAT = "On to %s, the next step in the guide.";
    public static final String NEXT_STEP_SHORTCUT = "Ctrl+Alt+Down";

    // ------------------------------------------------------------------ Components.swift: EducationalBanner

    /** The banner on every screen but the earnings ones. Its Quick Help is {@link #DISCLAIMER}. */
    public static final String EDUCATIONAL_BANNER = "Educational: real securities and prices from the security master; scenarios and simulations are labelled as such — not investment advice, not a forecast, and not a recommendation to buy or sell anything.";
    /** The banner on Earnings Calendar and Earnings by Day. */
    public static final String EDUCATIONAL_BANNER_REAL_DATA = "Educational use: real earnings reports from Nasdaq.com and FMP, as published — not investment advice and not a recommendation to buy or sell anything.";
    public static final String EDUCATIONAL_BANNER_HELP = "MxAcctMGR teaches how portfolio tools work. Talk to a licensed adviser about your own situation.";

    // ------------------------------------------------------------------ Components.swift: LessonCard, CollapsibleBox, RowsStepper

    /** The lesson card's header tooltip while it is collapsed. */
    public static final String LESSON_SHOW_HELP = "Show why this step matters";
    /** The lesson card's header tooltip while it is open. */
    public static final String LESSON_HIDE_HELP = "Hide the lesson";

    /** A collapsible box's header tooltip. Argument: the box's title in lower case. */
    public static final String COLLAPSIBLE_SHOW_HELP_FORMAT = "Show %s";
    public static final String COLLAPSIBLE_HIDE_HELP_FORMAT = "Hide %s";
    public static final String COLLAPSIBLE_SHOW = "Show";
    public static final String COLLAPSIBLE_HIDE = "Hide";

    /** The rows stepper's label. Argument: the number of rows (6–40, default 22, one setting for every table). */
    public static final String ROWS_LABEL_FORMAT = "Rows: %d";
    /** The rows stepper's tooltip. Argument: what the table is, "table" by default. */
    public static final String ROWS_HELP_FORMAT = "How many of the %s's rows show at once — the same for every table; the rest scroll inside it. Fewer leave room for the rest of the page.";
    public static final String ROWS_DEFAULT_WHAT = "table";

    // ------------------------------------------------------------------ Components.swift: MyPortfolioBar

    public static final String MY_PORTFOLIO_TITLE = "My Portfolio";
    public static final String MY_PORTFOLIO = "The open portfolio, kept in the security master (MxDataHUB's srm database) through the accounts service: holdings, tax lots, preferences and every saved version. One is open at a time — File ▸ New Portfolio…, Open Portfolio…, Save (Ctrl+S), Revert to Saved, Close Portfolio. Edits wait for Save; leaving a portfolio with unsaved changes asks to Save or Discard. Choose how you run it — active or passive index — and steps 4 and 5 follow.";

    /** With no portfolio open. */
    public static final String NO_PORTFOLIO_TITLE = "No portfolio open";
    public static final String NO_PORTFOLIO_TEXT = "Open one from the security master or create a new one; the steps then work on it.";
    public static final String NEW_PORTFOLIO = "New Portfolio…";
    public static final String OPEN_PORTFOLIO = "Open Portfolio…";

    /** The open portfolio's name line. Arguments: name, srm code. */
    public static final String PORTFOLIO_NAME_FORMAT = "%s · %s";
    public static final String NOT_SAVED_YET = "not saved yet";
    /** Argument: the open version number. */
    public static final String VERSION_FORMAT = "version %d";
    public static final String EDITED = "Edited";
    /** The Edited badge's tooltip: this, then the unsaved changes joined by "; ". */
    public static final String EDITED_HELP_PREFIX = "Not saved: ";
    /** Arguments: account type, total value as money, number of holdings, benchmark code. */
    public static final String PORTFOLIO_LINE_FORMAT = "%s · %s · %d holdings · vs %s";

    public static final String MY_APPROACH = "My approach";
    public static final String APPROACH_ACTIVE = "Active";
    public static final String APPROACH_PASSIVE = "Passive index";
    public static final String APPROACH_HELP = "Active: steps 4 and 5 are Optimize and Trades & Taxes. Passive: Build the Tracker and Rebalance Bands.";
    public static final String ACCOUNT_TYPE = "Account type";
    public static final String ACCOUNT_TYPE_HELP = "Taxable accounts pay tax on realised gains; retirement accounts don't when you trade";

    public static final String SAVE_BUTTON = "Save";
    public static final String SAVE_HELP = "Save the portfolio — holdings, lots, benchmark, approach and preferences — as its next version (Ctrl+S)";
    public static final String REVERT_BUTTON = "Revert";
    public static final String REVERT_HELP = "Drop the unsaved changes and read the saved version again (File ▸ Revert to Saved)";
    public static final String OPEN_BUTTON = "Open…";
    public static final String OPEN_HELP = "Open another portfolio (File ▸ Open Portfolio…, Ctrl+O)";
    public static final String NEW_BUTTON = "New…";
    public static final String NEW_HELP = "Create a portfolio in the security master (File ▸ New Portfolio…, Ctrl+N)";
    public static final String DECISIONS_BUTTON = "Decisions…";
    public static final String DECISIONS_HELP = "What was decided for this portfolio and why (Portfolio ▸ Decisions…, Ctrl+Alt+D)";
    /** Shown when a holding has no price. */
    public static final String LOAD_PRICES_BUTTON = "Load Prices";
    public static final String LOAD_PRICES_HELP = "Ask the srm-loader service to load prices for holdings without them (srm.py load nasdaq-prices: Nasdaq.com, free, no API key)";
    /** A menu of the sample accounts. */
    public static final String START_FROM_SAMPLE = "Start From a Sample";
    public static final String START_FROM_SAMPLE_HELP = "Replace the open portfolio's holdings with a copy of a sample account's holdings, lots and benchmark (saved when you save)";
}
