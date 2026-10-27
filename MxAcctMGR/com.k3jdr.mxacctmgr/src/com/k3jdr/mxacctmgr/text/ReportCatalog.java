package com.k3jdr.mxacctmgr.text;

import java.util.Comparator;
import java.util.List;

/**
 * The Report Library, verbatim from the Swift app: the tiers and the reports' library facts
 * ({@code MarketReports.swift}, {@code ReportLibrary.swift}), their official sources ({@code ReportSources.swift}),
 * and the static text of the Report Library page ({@code ReportsHubViews.swift}, {@code ReportNotes.swift}'s report
 * menu). The walkthroughs' own content (report lines, reading, terms, moves, reactions, takeaways, context series) is
 * not here. The page's subtitle and lesson card are {@link Lessons#of(String) Lessons.of("reportLibrary")}.
 *
 * <p>Page layout in the Swift: the lesson, "If you had to pick five" ({@link #TOP_FIVE_TITLE}), the driver matrix
 * ({@link #DRIVER_MATRIX_TITLE}), each tier's title and description with its reports as cards, then "Daily morning
 * reading" ({@link #MORNING_READING_TITLE}).
 */
public final class ReportCatalog {

    private ReportCatalog() {}

    /** What a report tells the market about: the four primary determinants of asset prices, plus positioning. */
    public enum Driver {
        GROWTH("Growth"),
        INFLATION("Inflation"),
        RATES("Interest rates"),
        PROFITS("Corporate profits"),
        SENTIMENT("Sentiment & positioning");

        /** The driver's name as the page shows it. */
        public final String label;

        Driver(String label) {
            this.label = label;
        }
    }

    /** Every driver, in the driver matrix's column order. */
    public static final List<Driver> DRIVERS = List.of(Driver.values());

    /** A tier: its number, title and description. */
    public record Tier(int id, String title, String description) {}

    /** The kinds of official page (ReportSource.Kind). */
    public enum SourceKind {
        RELEASE("Latest release"),
        SCHEDULE("Release schedule"),
        HISTORY("History"),
        LIVE("Live data");

        public final String label;

        SourceKind(String label) {
            this.label = label;
        }
    }

    /** One of the publisher's own pages for the real report. */
    public record Source(SourceKind kind, String publisher, String title, String url) {}

    /**
     * A report as the library shows it.
     *
     * @param id        the walkthrough's id ({@code MarketReport.id}), e.g. "jobs", "cpi"
     * @param title     its title
     * @param tier      1 ... 6 (see {@link #TIERS})
     * @param topFive   its rank among the five with the greatest impact, or {@code null}
     * @param publisher who publishes it (the card's first caption line)
     * @param schedule  when it comes out (the Swift {@code frequency}; the card's second caption line)
     * @param keyMetrics the key metrics
     * @param whyCare   why PMs care
     * @param drivers   the price drivers it moves
     * @param impact    typical market impact on the day: 1 low, 2 medium, 3 high ({@link #IMPACT_HELP})
     * @param industry  the industry, for Tier 6 reports; {@code null} otherwise
     * @param headline  the walkthrough's headline (its page subtitle). In "earnings" and "revisions", {@code {company}}
     *                  stands for the account's largest stock as "Name (TICKER)", else "a company in &lt;sector&gt;" or
     *                  "a large company".
     * @param sources   the official pages, the latest release first (the card links the first)
     */
    public record Report(String id, String title, int tier, Integer topFive, String publisher, String schedule,
            List<String> keyMetrics, List<String> whyCare, List<Driver> drivers, int impact, String industry,
            String headline, List<Source> sources) {}

    // ------------------------------------------------------------------ tiers

    public static final List<Tier> TIERS = List.of(
            new Tier(1, "Tier 1 · Market-Moving Economic Reports", "Released on a fixed schedule and priced within seconds: jobs, inflation and the Federal Reserve set expectations for growth, inflation and interest rates."),
            new Tier(2, "Tier 2 · Economic Growth Reports", "Whether the economy is accelerating or slowing: output, purchasing managers' surveys and consumer spending."),
            new Tier(3, "Tier 3 · Corporate Reports", "Stock prices ultimately follow earnings: results, guidance, and how analysts revise their estimates."),
            new Tier(4, "Tier 4 · Market Positioning Reports", "Who owns what and where money is moving: crowded trades and investor sentiment."),
            new Tier(5, "Tier 5 · Fixed Income Reports", "Demand for government debt and the shape of the yield curve: the price of money across maturities."),
            new Tier(6, "Tier 6 · Industry Reports", "Sector-specific data that specialists follow: semiconductors, banks, housing, energy."));

    // ------------------------------------------------------------------ sources

    private static Source release(String publisher, String title, String url) {
        return new Source(SourceKind.RELEASE, publisher, title, url);
    }

    private static Source schedule(String publisher, String title, String url) {
        return new Source(SourceKind.SCHEDULE, publisher, title, url);
    }

    private static Source history(String publisher, String title, String url) {
        return new Source(SourceKind.HISTORY, publisher, title, url);
    }

    private static Source live(String publisher, String title, String url) {
        return new Source(SourceKind.LIVE, publisher, title, url);
    }

    /** A FRED series page (the St. Louis Fed's database), as the Swift's {@code fred(_:_:)}. */
    private static Source fred(String title, String series) {
        return history("FRED (St. Louis Fed)", title, "https://fred.stlouisfed.org/series/" + series);
    }

    // ------------------------------------------------------------------ the reports, in tier order (MarketReport.all)

    public static final List<Report> REPORTS = List.of(
            new Report("jobs", "Jobs Report", 1, 2,
                    "U.S. Bureau of Labor Statistics (BLS)", "Monthly, first Friday, 8:30 a.m. ET",
                    List.of("Nonfarm payroll growth", "Unemployment rate", "Average hourly earnings", "Labor force participation rate"),
                    List.of("Strong jobs numbers can signal economic strength — but also inflation pressure.", "Weak numbers raise expectations of Federal Reserve rate cuts."),
                    List.of(Driver.GROWTH, Driver.INFLATION, Driver.RATES), 3, null,
                    "Hiring slows sharply: 48,000 jobs added vs 150,000 expected; unemployment rises to 4.6%",
                    List.of(release("BLS", "The Employment Situation", "https://www.bls.gov/news.release/empsit.nr0.htm"),
                            schedule("BLS", "Employment Situation release dates", "https://www.bls.gov/schedule/news_release/empsit.htm"),
                            fred("Nonfarm payrolls (PAYEMS)", "PAYEMS"),
                            fred("Unemployment rate (UNRATE)", "UNRATE"))),
            new Report("cpi", "CPI Inflation Report", 1, 1,
                    "U.S. Bureau of Labor Statistics (BLS)", "Monthly, around the 10th–15th, 8:30 a.m. ET",
                    List.of("Headline CPI", "Core CPI (ex food and energy)", "Monthly and annual inflation rates"),
                    List.of("Often the single biggest driver of interest-rate expectations.", "Influences bond yields, growth stocks, banks and consumer sectors."),
                    List.of(Driver.INFLATION, Driver.RATES), 3, null,
                    "Inflation runs hot: core CPI +0.5% in the month, 3.4% over the year — above every forecast",
                    List.of(release("BLS", "Consumer Price Index", "https://www.bls.gov/news.release/cpi.nr0.htm"),
                            schedule("BLS", "CPI release dates", "https://www.bls.gov/schedule/news_release/cpi.htm"),
                            fred("Core CPI, less food and energy (CPILFESL)", "CPILFESL"))),
            new Report("ppi", "PPI Producer Prices", 1, null,
                    "U.S. Bureau of Labor Statistics (BLS)", "Monthly, usually the day before or after CPI",
                    List.of("PPI final demand", "Core PPI", "Goods and services components"),
                    List.of("Often an early signal of future CPI trends.", "Important for assessing corporate margin pressure."),
                    List.of(Driver.INFLATION, Driver.PROFITS, Driver.RATES), 2, null,
                    "Producer prices jump 0.7%, led by goods and transport costs; margins under pressure",
                    List.of(release("BLS", "Producer Price Index", "https://www.bls.gov/news.release/ppi.nr0.htm"),
                            schedule("BLS", "PPI release dates", "https://www.bls.gov/schedule/news_release/ppi.htm"),
                            fred("PPI final demand (PPIFIS)", "PPIFIS"))),
            new Report("fomc", "FOMC Decision & Projections", 1, 3,
                    "Federal Reserve (FOMC)", "8 meetings a year; projections at 4 of them; 2:00 p.m. ET, press conference 2:30",
                    List.of("Interest-rate decision", "Dot plot", "Economic projections", "Chair's press conference"),
                    List.of("Can trigger major portfolio reallocations between equities, bonds, cash and international assets."),
                    List.of(Driver.RATES, Driver.INFLATION, Driver.GROWTH), 3, null,
                    "Fed holds rates at 4.00–4.25%; the dot plot shows one cut next year instead of three",
                    List.of(release("Federal Reserve", "FOMC meeting calendar, statements, projections and minutes", "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"),
                            release("Federal Reserve", "Press releases", "https://www.federalreserve.gov/newsevents/pressreleases.htm"),
                            live("CME Group", "FedWatch: market odds for the next meetings", "https://www.cmegroup.com/markets/interest-rates/cme-fedwatch-tool.html"),
                            fred("Fed funds target, upper limit (DFEDTARU)", "DFEDTARU"))),
            new Report("gdp", "GDP Report", 2, null,
                    "U.S. Bureau of Economic Analysis (BEA)", "Quarterly (advance, second and third estimates), 8:30 a.m. ET",
                    List.of("Real GDP growth", "Consumer spending", "Business investment", "Government spending"),
                    List.of("Determines whether the economy is accelerating or slowing."),
                    List.of(Driver.GROWTH, Driver.PROFITS), 2, null,
                    "Growth slows to 0.8% annualised as consumers and business investment cool",
                    List.of(release("BEA", "Gross Domestic Product", "https://www.bea.gov/data/gdp/gross-domestic-product"),
                            schedule("BEA", "Release schedule", "https://www.bea.gov/news/schedule"),
                            fred("Real GDP growth, annualised (A191RL1Q225SBEA)", "A191RL1Q225SBEA"))),
            new Report("ism-manufacturing", "ISM Manufacturing PMI", 2, 5,
                    "Institute for Supply Management (ISM)", "Monthly, first business day, 10:00 a.m. ET",
                    List.of("Headline PMI (50 = expansion / contraction)", "New orders", "Employment", "Prices paid"),
                    List.of("An early indicator of economic activity.", "Often moves cyclical stocks before earnings reports."),
                    List.of(Driver.GROWTH, Driver.INFLATION, Driver.PROFITS), 2, null,
                    "Factory activity contracts for a sixth month: PMI 46.8, new orders 44.1",
                    List.of(release("ISM", "PMI Reports: Manufacturing (latest and past month)", "https://www.ismworld.org/supply-management-news-and-reports/reports/ism-pmi-reports/"))),
            new Report("ism-services", "ISM Services PMI", 2, null,
                    "Institute for Supply Management (ISM)", "Monthly, third business day, 10:00 a.m. ET",
                    List.of("Services PMI (50 = expansion / contraction)", "Business activity", "New orders", "Prices paid"),
                    List.of("An early indicator of activity in most of the economy.", "Often moves cyclical stocks before earnings reports."),
                    List.of(Driver.GROWTH, Driver.INFLATION, Driver.RATES), 2, null,
                    "Services surge: PMI 56.4, highest in two years; prices paid also climb",
                    List.of(release("ISM", "PMI Reports: Services (latest and past month)", "https://www.ismworld.org/supply-management-news-and-reports/reports/ism-pmi-reports/"))),
            new Report("retail-sales", "Retail Sales", 2, null,
                    "U.S. Census Bureau", "Monthly, mid-month, 8:30 a.m. ET",
                    List.of("Headline retail sales", "Ex autos and gas", "Control group", "Category detail"),
                    List.of("Consumer spending is roughly two-thirds of U.S. economic activity.", "Critical for the consumer discretionary and retail sectors."),
                    List.of(Driver.GROWTH, Driver.PROFITS), 2, null,
                    "Shoppers pull back: retail sales −0.9%, control group −0.6%",
                    List.of(release("U.S. Census Bureau", "Advance Monthly Retail Trade", "https://www.census.gov/retail/sales.html"),
                            schedule("U.S. Census Bureau", "Economic indicator calendar", "https://www.census.gov/economic-indicators/calendar-listview.html"),
                            fred("Advance retail sales (RSAFS)", "RSAFS"))),
            new Report("earnings", "Earnings Report", 3, 4,
                    "The company (press release, 10-Q filing, conference call)", "Quarterly, mostly in the four 'earnings seasons'",
                    List.of("Revenue", "Earnings per share (EPS)", "Margins", "Guidance"),
                    List.of("Ultimately stock prices follow earnings.", "Guidance often matters more than the current quarter's results."),
                    List.of(Driver.PROFITS), 3, null,
                    "{company} misses on revenue and cuts guidance; shares fall 18% after hours (hypothetical)",
                    List.of(live("Nasdaq.com", "Earnings calendar", "https://www.nasdaq.com/market-activity/earnings"),
                            release("SEC EDGAR", "Company filings: 10-Q, 10-K and 8-K earnings releases (full-text search)", "https://www.sec.gov/edgar/search/"))),
            new Report("revisions", "Earnings Revisions", 3, null,
                    "Data providers: FactSet, Bloomberg, Visible Alpha, Refinitiv (LSEG)", "Continuous; most followed weekly",
                    List.of("Consensus EPS and revenue", "Up vs down revisions (breadth)", "Price targets", "Forward P/E"),
                    List.of("Analysts revising estimates up or down often precede stock movements."),
                    List.of(Driver.PROFITS, Driver.SENTIMENT), 1, null,
                    "Analysts raise estimates for {company} for a fourth week; the stock is up 11% in a month (hypothetical)",
                    List.of(release("FactSet", "Earnings Insight: weekly estimate revisions and S&P 500 earnings", "https://insight.factset.com/topic/earnings"))),
            new Report("cot", "CFTC Commitments of Traders", 4, null,
                    "U.S. Commodity Futures Trading Commission (CFTC)", "Weekly, Friday 3:30 p.m. ET (as of Tuesday)",
                    List.of("Hedge-fund (leveraged) positioning", "Commercial hedging", "Speculative activity", "Treasury, equity-index, commodity and currency futures"),
                    List.of("Identifies crowded trades."),
                    List.of(Driver.SENTIMENT), 1, null,
                    "Speculators hold record net long in stock index futures and record short in 10-year Treasury futures",
                    List.of(release("CFTC", "Commitments of Traders", "https://www.cftc.gov/MarketReports/CommitmentsofTraders/index.htm"),
                            schedule("CFTC", "Release schedule", "https://www.cftc.gov/MarketReports/CommitmentsofTraders/ReleaseSchedule/index.htm"))),
            new Report("fund-flows", "Fund Flow Report", 4, null,
                    "Data providers: EPFR, Morningstar, LSEG Lipper", "Weekly (EPFR, Lipper), monthly (Morningstar)",
                    List.of("Equity, bond and money-market flows", "ETF vs mutual-fund flows", "Regional flows"),
                    List.of("Reveals investor sentiment and liquidity trends."),
                    List.of(Driver.SENTIMENT), 1, null,
                    "Investors pull $24 billion from stock funds, the most in a year; money-market funds take in $41 billion",
                    List.of(release("ICI", "Weekly combined estimated ETF and long-term fund flows", "https://www.ici.org/research/statistics/etfs/weekly-combined-estimated-etf-and-longterm-flows"),
                            release("ICI", "Weekly money market fund assets", "https://www.ici.org/weekly-money-market-mutual-fund-assets"))),
            new Report("auctions", "Treasury Auction Results", 5, null,
                    "U.S. Department of the Treasury (TreasuryDirect)", "Weekly bills; 2-, 5-, 7-year monthly; 10- and 30-year monthly with reopenings",
                    List.of("High yield vs when-issued (tail)", "Bid-to-cover", "Indirect, direct and dealer shares", "2-, 5-, 10- and 30-year results"),
                    List.of("Demand levels can move interest rates immediately."),
                    List.of(Driver.RATES), 2, null,
                    "Weak 10-year auction: 3.2 basis-point tail, lowest bid-to-cover in two years; long yields jump",
                    List.of(release("TreasuryDirect", "Auction results", "https://www.treasurydirect.gov/auctions/auction-results/"),
                            schedule("TreasuryDirect", "Auction announcements (upcoming auctions)", "https://www.treasurydirect.gov/auctions/auction-announcements/"),
                            history("TreasuryDirect", "Auction query: every past auction", "https://www.treasurydirect.gov/auctions/auction-query/"))),
            new Report("yield-curve", "Yield Curve Report", 5, null,
                    "U.S. Treasury market (daily yields from the Treasury Department)", "Continuous; most watched spreads: 2Y–10Y and 3M–10Y",
                    List.of("2-year, 10-year and 30-year yields", "2Y–10Y spread", "3M–10Y spread", "Inflation breakevens"),
                    List.of("An inverted curve has preceded many recessions.", "The slope influences sector allocation: banks, utilities, real estate and long-duration growth stocks all depend on it."),
                    List.of(Driver.RATES, Driver.GROWTH, Driver.INFLATION), 2, null,
                    "Long-term yields jump: 10-year at 4.55% (+0.28), 30-year at 4.95%; the curve steepens as deficit and inflation worries mount",
                    List.of(live("U.S. Treasury", "Daily Treasury par yield curve rates", "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/TextView?type=daily_treasury_yield_curve"),
                            fred("10-year minus 2-year spread (T10Y2Y)", "T10Y2Y"),
                            fred("10-year minus 3-month spread (T10Y3M)", "T10Y3M"))),
            new Report("semiconductors", "Semiconductor Sales (Technology)", 6, null,
                    "Industry association monthly sales data; cloud-spending surveys; research firms such as Gartner", "Monthly sales; quarterly surveys and forecasts",
                    List.of("Semiconductor sales", "Cloud spending surveys", "IT spending forecasts", "Inventories"),
                    List.of("Early read on the technology cycle, a third of the index."),
                    List.of(Driver.PROFITS, Driver.GROWTH), 1, "Technology",
                    "Chip sales fall 6% in the month as cloud providers pause data-center orders; technology stocks slide",
                    List.of(release("SIA", "Semiconductor Industry Association news: monthly global chip sales", "https://www.semiconductors.org/news-events/latest-news/"),
                            release("WSTS", "World Semiconductor Trade Statistics", "https://www.wsts.org/"))),
            new Report("banks", "Bank Earnings & Credit (Financials)", 6, null,
                    "Bank quarterly results; Federal Reserve weekly bank data (H.8); credit bureaus", "Quarterly results; weekly loan data",
                    List.of("Bank earnings", "Loan growth", "Credit quality (provisions, charge-offs)", "Capital ratios"),
                    List.of("Credit quality warns of stress before the economy data does."),
                    List.of(Driver.PROFITS, Driver.GROWTH), 1, "Financials",
                    "A large bank sets aside more for bad loans; credit-card charge-offs at a 10-year high",
                    List.of(release("FDIC", "Quarterly Banking Profile", "https://www.fdic.gov/quarterly-banking-profile"),
                            release("Federal Reserve", "H.8 Assets and Liabilities of Commercial Banks (weekly)", "https://www.federalreserve.gov/releases/h8/current/default.htm"),
                            history("Federal Reserve", "Charge-off and delinquency rates", "https://www.federalreserve.gov/releases/chargeoff/"))),
            new Report("housing", "Housing Starts & Home Sales (Housing)", 6, null,
                    "U.S. Census Bureau (new residential construction); realtors' association (existing-home sales)", "Monthly",
                    List.of("Housing starts", "Building permits", "Existing home sales", "Prices and mortgage rates"),
                    List.of("The most rate-sensitive part of the economy; leads construction and home-related spending."),
                    List.of(Driver.GROWTH, Driver.RATES), 1, "Housing",
                    "Housing starts drop 11%; permits at a five-year low as mortgage rates hold near 7%",
                    List.of(release("U.S. Census Bureau", "New Residential Construction: starts and permits", "https://www.census.gov/construction/nrc/index.html"),
                            release("U.S. Census Bureau", "New Residential Sales", "https://www.census.gov/construction/nrs/index.html"),
                            release("National Association of Realtors", "Existing-home sales", "https://www.nar.realtor/research-and-statistics/housing-statistics/existing-home-sales"),
                            live("Freddie Mac", "Primary Mortgage Market Survey: weekly mortgage rates", "https://www.freddiemac.com/pmms"),
                            fred("Housing starts (HOUST)", "HOUST"))),
            new Report("energy", "EIA Petroleum Status (Energy)", 6, null,
                    "U.S. Energy Information Administration (EIA); OPEC Monthly Oil Market Report", "Weekly (EIA, Wednesday); monthly (OPEC)",
                    List.of("Crude and gasoline inventories", "Refinery utilisation", "OPEC production", "Oil price"),
                    List.of("Drives energy stocks, inflation expectations and costs across the economy."),
                    List.of(Driver.INFLATION, Driver.PROFITS), 1, "Energy",
                    "Crude inventories jump 9.6 million barrels as OPEC+ signals higher output; oil falls 6%",
                    List.of(release("EIA", "Weekly Petroleum Status Report", "https://www.eia.gov/petroleum/supply/weekly/"),
                            schedule("EIA", "Release schedule", "https://www.eia.gov/petroleum/supply/weekly/schedule.php"),
                            release("EIA", "Short-Term Energy Outlook (monthly)", "https://www.eia.gov/outlooks/steo/"))));

    /** The five with the greatest impact, by rank (CPI, jobs, FOMC, earnings, ISM manufacturing). */
    public static final List<Report> TOP_FIVE = REPORTS.stream()
            .filter(r -> r.topFive() != null)
            .sorted(Comparator.comparing(Report::topFive))
            .toList();

    /** A report by id; as in the Swift, an unknown id gives the jobs report. */
    public static Report report(String id) {
        return REPORTS.stream().filter(r -> r.id().equals(id)).findFirst().orElse(REPORTS.get(0));
    }

    /** A tier's reports, in library order. */
    public static List<Report> reportsInTier(int tier) {
        return REPORTS.stream().filter(r -> r.tier() == tier).toList();
    }

    // ------------------------------------------------------------------ the Report Library page's own text

    /** "If you had to pick five": the box's title and Quick Help, then the five as cards, then {@link #TOP_FIVE_TEXT}. */
    public static final String TOP_FIVE_TITLE = "If you had to pick five";
    public static final String TOP_FIVE_HELP = "For a diversified U.S. equity portfolio, the reports with the greatest impact are usually these five. Together they drive expectations for growth, inflation, interest rates and profits.";
    public static final String TOP_FIVE_TEXT = "These five collectively drive expectations for economic growth, inflation, interest rates and corporate profits — the four primary determinants of asset prices.";
    /** A top-five card shows the rank, the title and its drivers' labels joined by this. */
    public static final String DRIVER_SEPARATOR = " · ";
    /** A top-five card's tooltip. Argument: the report's title. */
    public static final String OPEN_WALKTHROUGH_HELP_FORMAT = "Open the %s walkthrough";
    /** A top-five card's Quick Help: title = the report's title; detail = its why-PMs-care lines and this, joined by " ". */
    public static final String TOP_FIVE_QUICK_HELP_END = "Open its walkthrough.";

    /** The driver matrix: a row per report (its title as a link), a column per driver (a dot where it moves it), then Impact. */
    public static final String DRIVER_MATRIX_TITLE = "Which drivers each report moves";
    public static final String DRIVER_MATRIX_HELP = "A dot where a report is news about that driver of asset prices. Right-click a report for your own notes on it; rest the pointer on its name to read them.";
    public static final String DRIVER_MATRIX_REPORT = "Report";
    public static final String DRIVER_MATRIX_IMPACT = "Impact";
    /** A matrix cell's text when copied: a driver the report moves, or not. */
    public static final String DRIVER_MATRIX_YES = "✓";

    /** The impact dots' tooltip, by impact level (index 0 unused). */
    public static final List<String> IMPACT_HELP = List.of("", "Usually a small market reaction on the day", "Can move markets on a surprise", "Moves markets within seconds of release");
    /** The impact level's words when copied, by impact level (index 0 unused). */
    public static final List<String> IMPACT_WORDS = List.of("", "low", "medium", "high");

    /** A report card: title, publisher, schedule; "Top five · #n" badge and impact dots; key metrics; why PMs care; drivers. */
    public static final String TOP_FIVE_BADGE_FORMAT = "Top five · #%d";
    /** The key metrics line: this, then the metrics joined by " · ". */
    public static final String KEY_METRICS_PREFIX = "Key metrics: ";
    public static final String OPEN_WALKTHROUGH = "Open Walkthrough";
    public static final String OPEN_WALKTHROUGH_HELP = "Read this report and simulate your reaction";
    /** The card's link to its first source. Arguments: the source's publisher, its title. */
    public static final String SOURCE_LINK_FORMAT = "%s: %s";
    /** The source link's tooltip. Arguments: the source's publisher, its URL. */
    public static final String SOURCE_LINK_HELP_FORMAT = "The real report, from %s: %s";
    public static final String OFFICIAL_SOURCE_TITLE = "Official Source";
    /** The source link's Quick Help. Arguments: the report's title, the source's title, the source's publisher. */
    public static final String OFFICIAL_SOURCE_FORMAT = "Opens the real %s — %s, from %s — in your browser. The walkthrough's numbers are illustrative; its first step lists every official page (release, schedule, history).";
    /**
     * A report card's Quick Help: title = the report's title. Arguments: publisher, schedule, the key metrics joined
     * by ", ", the why-PMs-care lines joined by " ".
     */
    public static final String REPORT_CARD_QUICK_HELP_FORMAT = "%s. %s. Key metrics: %s. Why PMs care: %s";

    /** A report's context menu (on its name in the matrix). Add Notes… when it has none, else Edit Notes…. */
    public static final String ADD_NOTES = "Add Notes…";
    public static final String EDIT_NOTES = "Edit Notes…";
    /** Argument: the first source's publisher. */
    public static final String OPEN_OFFICIAL_SOURCE_FORMAT = "Open Official Source (%s)";
    public static final String COPY_OFFICIAL_SOURCE = "Copy Official Source Address";
    /** A report name's accessibility hint, without and with notes. */
    public static final String REPORT_LINK_HINT = "Opens its walkthrough";
    public static final String REPORT_LINK_HINT_NOTES = "Opens its walkthrough. Has your notes.";
    /** The note mark after a report's name when it has notes. */
    public static final String NOTES_MARK_HELP = "Your notes on this report: rest the pointer on its name to read them, right-click it to edit them";

    /** The last box: what PMs read each morning, and buttons to the Morning Briefing, the VIX and Compute. */
    public static final String MORNING_READING_TITLE = "Daily morning reading";
    public static final String MORNING_READING_HELP = "The ten things many institutional PMs review before the open.";
    public static final String MORNING_READING_TEXT = "Many institutional PMs start the day with overnight markets, Treasury yields, the economic calendar, economic surprise indexes, Fed funds futures, analyst upgrades and downgrades, earnings releases, fund flows, credit spreads and the VIX.";
    /** Opens {@code morningBriefing}. */
    public static final String OPEN_MORNING_BRIEFING = "Open the Morning Briefing";
    /** Opens {@code vix}. */
    public static final String OPEN_VIX = "The VIX, real data";
    public static final String OPEN_VIX_HELP = "The Cboe Volatility Index from Cboe: today, its history, term structure and past spikes";
    /** Opens {@code compute}. */
    public static final String OPEN_COMPUTE = "Compute Hyperscalers Tracking: capex";
    public static final String OPEN_COMPUTE_HELP = "What Microsoft, Alphabet, Amazon, Meta and Oracle spend on AI compute, and their suppliers' revenue — reported figures";
}
