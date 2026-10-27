package com.k3jdr.mxacctmgr.text;

import java.util.List;

/**
 * The static content of the lesson-only pages, verbatim from the Swift app's {@code LearnViews.swift}: Welcome, Choose
 * Your Path, Why Optimize?, Why Attribution? and Active vs Passive. Each page's subtitle and lesson card are in
 * {@link Lessons} (keys {@code welcome, choosePath, whyOptimize, whyAttribution, activeVsPassive}).
 *
 * <p>A {@link Box} is a boxed section: its title and the Quick Help detail of the whole box ({@code null} when the
 * Swift gives none). A {@code _FORMAT} constant takes {@link String#format} arguments, documented on it; percentages
 * there are formatted as in the Swift {@code pct(x, digits)} = {@code String.format("%.{digits}f%%", 100 * x)} (digits
 * 1 by default) and {@code signedPct(x, digits)} = {@code String.format("%+.{digits}f%%", 100 * x)} (digits 2 by
 * default); money as US dollars, no cents from $1,000 up.
 */
public final class LearnTexts {

    private LearnTexts() {}

    /** A title and its text (a card, a step, a Quick Help item). */
    public record Section(String title, String text) {}

    /** A boxed section's title and Quick Help detail ({@code null} if none). */
    public record Box(String title, String help) {}

    /** A Welcome card: its title, text, and the screen key it opens. */
    public record Card(String title, String text, String target) {}

    /** A Choose Your Path question with its active and passive answers. */
    public record Question(String question, String active, String passive) {}

    /** A table row's cells. */
    public record Row(String... cells) {}

    /** A demo slider: its label (also its tooltip unless noted), range, step and starting value. */
    public record Slider(String label, double min, double max, double step, double initial) {}

    // ================================================================== Welcome

    /** The three cards across the top. Tooltip: {@link #WELCOME_CARD_HELP_FORMAT}; Quick Help: the card's title and text. */
    public static final List<Card> WELCOME_CARDS = List.of(
            new Card("1. Choose a Path",
                    "Active: beat a benchmark within a risk budget. Passive: match an index cheaply and stay matched. Then work through My Portfolio.",
                    "choosePath"),
            new Card("2. Read the News",
                    "Eighteen reports portfolio managers watch — jobs, CPI, the Fed, earnings, the yield curve and more — a morning briefing, and real earnings by day and month. Simulate your reaction to each over twenty years.",
                    "reportLibrary"),
            new Card("3. Learn the Why",
                    "Why a portfolio needs optimizing, why results need attributing, and what active and passive cost — in Knowledge Library.",
                    "whyOptimize"));

    /** A card's tooltip. Argument: the target screen's full title. */
    public static final String WELCOME_CARD_HELP_FORMAT = "Go to %s";

    public static final Box WELCOME_HOW_TO = new Box("How to use it", "How the window is laid out and how Quick Help works.");

    /** The "How to use it" lines (each with an icon in the Swift: sidebar, Quick Help bubble, person, briefcase, cpu). */
    public static final List<String> WELCOME_HOW_TO_LINES = List.of(
            "The sidebar is the guide: My Portfolio has six steps; Ctrl+Alt+Down and Ctrl+Alt+Up move to the next and previous screen, and Ctrl+[ and Ctrl+] go back and forward, as in a browser.",
            "Quick Help, under the sidebar, explains whatever is under the pointer — a number, a column, a button. Ctrl+Alt+/ shows or hides it; its ⧉ button floats it in its own window.",
            "Investments, in the sidebar, walks your own account (My Portfolio) through six steps — active or passive, your choice.",
            "Pick a sample account in the toolbar for the report walkthroughs and the Morning Briefing: each teaches an edge case. Library ▸ Sample Portfolios describes them all; copy one into My Portfolio to work through it.",
            "Engine in the toolbar: the built-in optimizers, or the shared Layer 3 services (portfolio-java, portfolio-cpp, analytics) that MxDataHUB and MxJobsMGR use.");

    /** The selected account's box: the account's name, its lesson, and a button to Sample Portfolios. No Quick Help. */
    public static final Box WELCOME_ACCOUNT = new Box("The account now selected", null);
    public static final String WELCOME_ALL_SAMPLES = "See All Sample Portfolios";

    // ================================================================== Choose Your Path

    /** The question table's header row. */
    public static final Row CHOOSE_PATH_HEADER = new Row("Question", "Active", "Passive");

    public static final List<Question> CHOOSE_PATH_QUESTIONS = List.of(
            new Question("Do you believe you (or a manager) can pick winners consistently, after costs?",
                    "Active needs a real, repeatable edge — most professionals fail to beat their index after fees over long periods.",
                    "Passive accepts the market's return and spends its effort on cost and discipline."),
            new Question("How much do you want results to differ from the market's?",
                    "Active means accepting tracking error: some years well ahead, some well behind.",
                    "Passive keeps you close to the index — in good years and bad."),
            new Question("How much time will you spend?",
                    "Active needs research, monitoring and an honest review (attribution) every year.",
                    "Passive needs a rebalance rule and the patience to follow it."),
            new Question("Is the account taxable?",
                    "Frequent active trading realises gains, often short-term.",
                    "Index funds and wide rebalance bands defer taxes."));

    /** A question row's Quick Help: title = the question; detail arguments: the active answer, the passive answer. */
    public static final String CHOOSE_PATH_QUESTION_HELP_FORMAT = "Active: %s Passive: %s";

    public static final String START_TITLE = "Ready to work on your own account?";
    public static final String START_TEXT = "Investments walks your own holdings (My Portfolio) through six steps: choose Active or Passive index there, and steps 4 and 5 follow your choice. To learn on a sample first, copy one into My Portfolio (Library ▸ Sample Portfolios, or Start From a Sample).";
    /** Opens My Holdings ({@code myPortfolio}). */
    public static final String START_BUTTON = "Start My Portfolio";
    /** The start panel's Quick Help. */
    public static final Section START_QUICK_HELP = new Section("Investments",
            "The six steps on your own account: My Holdings, My Benchmark, Risk Today, Optimize or Build the Tracker, Trades & Taxes or Rebalance Bands, Attribution.");

    // ================================================================== Why Optimize?

    /*
     * The two demos (WhyOptimizeView.load), both from the analytics service's risk inputs (Ledoit-Wolf covariance on two
     * years of real daily prices):
     *
     * Diversification: cov = the S&P 500 members' annual covariance (n members, benchmark "SP500"). For each
     *   k = 1 ... min(50, n): the average over 40 random draws (a seeded generator, seed 5) of
     *   sqrt( sum over i, j in the k picks of cov[i][j] / k^2 ), i.e. the volatility of an equal-weighted portfolio of
     *   k members picked at random. Chart: volatility % against k, with a dashed rule at the market's volatility
     *   (VTI's, Universe.marketVol) labelled "market risk", and a point at the slider's k (default 5).
     *   Caption's bad-year fall = the volatility minus 0.08 (pct(vol - 0.08)).
     *
     * Trade-off: cov of VTI and BND; expected return of each = riskFree + beta * 0.05 (riskFree = the 3-month Treasury
     *   yield; 0.05 = the assumed equity premium; cash would be riskFree alone). For each stock share
     *   x = 0, 0.05, ..., 1:
     *     variance = x^2 * cov(V,V) + (1-x)^2 * cov(B,B) + 2 * x * (1-x) * cov(V,B), volatility = sqrt(variance)
     *     return   = x * r(VTI) + (1-x) * r(BND)
     *   Chart: return % against volatility %; the point nearest the slider's share (default 0.6, step 0.05) is
     *   labelled "60/40" (stocks/bonds as whole percents).
     */

    public static final int OPTIMIZE_DEFAULT_NAMES = 5;
    public static final int OPTIMIZE_MAX_NAMES = 50;
    public static final int OPTIMIZE_DRAWS = 40;
    public static final double OPTIMIZE_DEFAULT_STOCK_SHARE = 0.6;
    public static final double OPTIMIZE_EQUITY_PREMIUM = 0.05;

    /** Shown instead of the demos when the analytics service fails (the error text follows, then Try Again). */
    public static final Box OPTIMIZE_NEEDS_SERVICE = new Box("Needs the analytics service",
            "These charts are computed from real prices in the security master by the analytics service.");
    public static final String OPTIMIZE_TRY_AGAIN = "Try Again";
    /** The error shown there when the analytics service has no prices for VTI or BND. */
    public static final String OPTIMIZE_NO_VTI_BND = "VTI or BND isn't in the security master with prices";

    /** Shown while the demos load. */
    public static final Box OPTIMIZE_LOADING = new Box("Diversification and the stock / bond trade-off",
            "Reading the S&P 500's and VTI / BND's risk from the analytics service.");
    public static final String OPTIMIZE_LOADING_TEXT = "Estimating risk from two years of real prices (analytics service)…";

    public static final Box DIVERSIFICATION = new Box("Diversification: own risk fades, market risk stays",
            "Volatility of an equal-weighted portfolio of S&P 500 members chosen at random (the average of 40 draws), from two years of real daily prices. The first few names cut risk sharply; then the line flattens near the market's own volatility (VTI's, dashed).");
    public static final String DIVERSIFICATION_MARKET_LABEL = "market risk";
    public static final String DIVERSIFICATION_Y_AXIS = "Volatility %";
    public static final String DIVERSIFICATION_X_AXIS = "Companies held (equal weights)";
    /** Slider 1 ... the number of points (at most 50), step 1, starting at 5. */
    public static final String DIVERSIFICATION_SLIDER = "Companies";
    public static final String DIVERSIFICATION_SLIDER_HELP = "Number of companies held in equal weights";

    /** The diversification caption. {@code volatility} and {@code badYearFall} formatted with pct (1 digit). */
    public static String diversificationCaption(int names, String volatility, String badYearFall) {
        return names + " compan" + (names == 1 ? "y" : "ies") + ": volatility " + volatility
                + " a year — in a bad year (about 1 in 6) the account could fall " + badYearFall + " or more.";
    }

    public static final Box TRADE_OFF = new Box("The trade-off: stocks for growth, bonds for stability",
            "Every mix of VTI (U.S. stocks) and BND (U.S. bonds), from all bonds (bottom left) to all stocks (top right): volatility from two years of real prices; expected return = the 3-month Treasury yield + beta × an assumed 5% equity premium.");
    /** The selected point's label. Arguments: stocks %, bonds % (whole numbers). */
    public static final String TRADE_OFF_POINT_FORMAT = "%d/%d";
    public static final String TRADE_OFF_X_AXIS = "Volatility %";
    public static final String TRADE_OFF_Y_AXIS = "Expected return % (CAPM, assumed premium)";
    /** Slider 0 ... 1, step 0.05, starting at 0.6. */
    public static final String TRADE_OFF_SLIDER = "Stocks";
    public static final String TRADE_OFF_SLIDER_HELP = "Share of stocks in the mix";
    /**
     * The trade-off caption. Arguments: stocks % (int), bonds % (int), expected return pct(ret), the 3-month yield
     * pct(riskFree, 2), the premium pct(0.05, 0), the volatility pct(vol).
     */
    public static final String TRADE_OFF_CAPTION_FORMAT = "%d%% VTI / %d%% BND: about %s a year expected (the 3-month yield %s plus beta × a %s premium, an assumption) with %s volatility, measured.";

    public static final Box OPTIMIZER = new Box("What an optimizer does", "The optimizer's three inputs and its output.");
    /** The optimizer's inputs and output, left to right, joined by {@link #OPTIMIZER_JOINS}; each is also its Quick Help. */
    public static final List<Section> OPTIMIZER_STEPS = List.of(
            new Section("Forecasts", "Expected returns or alpha: what you believe about each holding. Small errors here produce large errors in weights."),
            new Section("Risk model", "How every pair of holdings moves together (a covariance matrix). Here: estimated from two years of real daily prices in the security master, shrunk toward a simpler model (Ledoit-Wolf) so it stays stable."),
            new Section("Limits", "Largest position, how far from the benchmark, sector bands, a tracking-error budget, how much to trade."),
            new Section("Weights & trades", "The mix with the best forecast reward per unit of risk inside the limits — and the trades to get there."));
    /** The symbols between the steps (SF Symbols plus, plus, arrow.right in the Swift). */
    public static final List<String> OPTIMIZER_JOINS = List.of("+", "+", "→");
    public static final String OPTIMIZER_NOTE = "Without limits an optimizer bets heavily on whatever its forecasts like most — 'error maximisation'. Investments' Optimize step (the active approach) lets you change each limit and see what binds.";

    // ================================================================== Why Attribution?

    /*
     * The two-sector demo (Brinson-Fachler). Sectors: 0 = technology, 1 = the rest.
     *   account weights   wp = [techWeight, 1 - techWeight]        account returns   rp = [techReturn, restReturn]
     *   benchmark weights wb = [0.30, 0.70]                         benchmark returns rbs = [0.20, 0.08]
     *   rb    = sum wb[i] * rbs[i]                (benchmark return, 0.116)
     *   total = sum wp[i] * rp[i]                 (account return; active return = total - rb)
     *   allocation  = sum (wp[i] - wb[i]) * (rbs[i] - rb)
     *   selection   = sum wb[i] * (rp[i] - rbs[i])
     *   interaction = sum (wp[i] - wb[i]) * (rp[i] - rbs[i])
     * The three always add up to total - rb. Chart: bars Allocation, Selection, Interaction (green positive, red
     * negative) and Active (blue), in percentage points (x 100).
     */

    public static final double ATTRIBUTION_BENCH_TECH_WEIGHT = 0.30;
    public static final double ATTRIBUTION_BENCH_TECH_RETURN = 0.20;
    public static final double ATTRIBUTION_BENCH_REST_RETURN = 0.08;

    /** The account's sliders: technology weight, technology return, other return. Value shown as pct(x, 0). */
    public static final List<Slider> ATTRIBUTION_SLIDERS = List.of(
            new Slider("Technology weight in the account", 0.0, 0.9, 0.05, 0.45),
            new Slider("Your technology holdings returned", -0.1, 0.4, 0.01, 0.14),
            new Slider("Your other holdings returned", -0.1, 0.3, 0.01, 0.09));

    public static final Box ATTRIBUTION_DEMO = new Box("Two sectors: change the account",
            "Move the sliders to change the account's technology weight and the returns of its holdings; the benchmark is 30% technology returning 20% and 70% everything else returning 8%.");
    /**
     * Arguments: the benchmark's technology weight pct(0.30, 0), its return pct(0.20, 0), the rest's weight
     * pct(0.70, 0), its return pct(0.08, 0), the benchmark's total pct(rb).
     */
    public static final String ATTRIBUTION_BENCHMARK_FORMAT = "Benchmark: technology %s at %s, the rest %s at %s → %s.";
    /** Arguments: the account's return pct(total), the active return signedPct(total - rb). */
    public static final String ATTRIBUTION_ACCOUNT_FORMAT = "Account: %s → active return %s.";

    public static final Box ATTRIBUTION_EFFECTS = new Box("Where the active return came from",
            "Brinson-Fachler effects of the two-sector example: they always add up to the active return.");
    public static final List<String> ATTRIBUTION_BARS = List.of("Allocation", "Selection", "Interaction", "Active");
    public static final String ATTRIBUTION_Y_AXIS = "percentage points";

    /** The story under the chart: the effect largest in size (the first if tied), in these words. */
    public static final List<String> ATTRIBUTION_STORY_EFFECTS = List.of(
            "allocation (being overweight or underweight technology)", "selection (which companies you held)", "interaction");
    /** Arguments: the effect's words, its value signedPct(x). Followed by the over- or underweight sentence. */
    public static final String ATTRIBUTION_STORY_FORMAT = "Most of the difference came from %s: %s. ";
    /** When the technology weight is above the benchmark's 30%. */
    public static final String ATTRIBUTION_STORY_OVERWEIGHT = "Being overweight technology helps when technology beats the whole benchmark; it hurts when it doesn't.";
    /** When it is not. */
    public static final String ATTRIBUTION_STORY_UNDERWEIGHT = "Being underweight technology costs return when technology beats the whole benchmark.";

    /** The story under the chart, built as in the Swift {@code story(_:_:_:)}. */
    public static String attributionStory(double allocation, double selection, double interaction, double techWeight) {
        double[] effects = {allocation, selection, interaction};
        int big = 0;
        for (int i = 1; i < effects.length; i++) {
            if (Math.abs(effects[big]) < Math.abs(effects[i])) big = i;
        }
        return String.format(ATTRIBUTION_STORY_FORMAT, ATTRIBUTION_STORY_EFFECTS.get(big),
                String.format("%+.2f%%", 100 * effects[big]))
                + (techWeight > ATTRIBUTION_BENCH_TECH_WEIGHT ? ATTRIBUTION_STORY_OVERWEIGHT : ATTRIBUTION_STORY_UNDERWEIGHT);
    }

    /** The glossary chips under the demo; each one's tooltip and Quick Help is {@link Help#term(String)}. */
    public static final List<String> ATTRIBUTION_TERMS = List.of("Allocation effect", "Selection effect", "Interaction effect", "Carino linking");

    // ================================================================== Active vs Passive

    /*
     * The fee demo: two funds from START over YEARS years, gross return GROSS a year before fees.
     *   index fund  value(y) = START * (1 + GROSS - PASSIVE_FEE)^y
     *   active fund value(y) = START * (1 + GROSS + alpha - activeFee)^y      (y = 0 ... 30)
     * Chart: both lines, value as money on the y axis. Break-even alpha = activeFee - PASSIVE_FEE.
     */

    public static final double FEES_PASSIVE_FEE = 0.0005;
    public static final double FEES_GROSS = 0.065;
    public static final double FEES_START = 100_000.0;
    public static final int FEES_YEARS = 30;

    /** The active fee: 0.1% ... 1.5%, step 0.05%, starting at 0.75%. Label: {@link #FEES_ACTIVE_FEE_FORMAT}. */
    public static final Slider FEES_ACTIVE_FEE = new Slider("Annual fee of the active fund", 0.001, 0.015, 0.0005, 0.0075);
    /** The active manager's return before fees vs the index: −1% ... +2%, step 0.05%, starting at 0. Label: {@link #FEES_ALPHA_FORMAT}. */
    public static final Slider FEES_ALPHA = new Slider("How much the manager beats (or trails) the index before fees each year", -0.01, 0.02, 0.0005, 0.0);

    public static final Box FEES = new Box("What fees compound to",
            "$100,000 over 30 years at an illustrative 6.5% a year before fees. Move the active fee and the alpha the active manager must add to break even.");
    public static final String FEES_INDEX_FUND = "Index fund";
    public static final String FEES_ACTIVE_FUND = "Active fund";
    /** The fee slider's label (its tooltip is the Slider's label). Argument: pct(activeFee, 2). */
    public static final String FEES_ACTIVE_FEE_FORMAT = "Active fee %s a year";
    /** The alpha slider's label (its tooltip is the Slider's label). Argument: signedPct(alpha, 2). */
    public static final String FEES_ALPHA_FORMAT = "Active return before fees vs the index: %s";

    /** The caption under the chart, as in the Swift. Money arguments formatted as US dollars. */
    public static String feesCaption(int years, String indexEnd, String activeEnd, boolean activeAhead, String difference,
            String breakEven) {
        return "After " + years + " years: index fund " + indexEnd + ", active fund " + activeEnd + " — "
                + (activeAhead ? "ahead by" : "behind by") + " " + difference
                + ". To break even the active manager must add " + breakEven + " a year, every year, before fees.";
    }

    /** The comparison table: header row (first cell empty), then the rows; the first column is shown secondary. */
    public static final Row COMPARISON_HEADER = new Row("", "Active", "Passive");
    public static final List<Row> COMPARISON_ROWS = List.of(
            new Row("Goal", "Beat the benchmark after costs", "Match the index as cheaply as possible"),
            new Row("Typical tracking error", "3–8% a year", "0.05–1% a year"),
            new Row("Typical fees", "0.4–1.2% a year", "0.03–0.20% a year"),
            new Row("Turnover", "30–100%+ a year", "2–10% a year"),
            new Row("Taxes (taxable account)", "Gains realised along the way", "Mostly deferred until you sell"),
            new Row("Main tool here", "Active optimizer (portfolio-java)", "Index tracker (portfolio-cpp)"),
            new Row("Attribution should show", "The bets you meant to make paying", "Effects close to zero"));
    /** The table's Quick Help. */
    public static final Section COMPARISON_QUICK_HELP = new Section("Active vs Passive",
            "Typical ranges for funds and accounts; illustrative, not rules.");
}
