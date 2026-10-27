package com.k3jdr.mxdatahub;

import com.k3jdr.mx.core.ui.QuickHelpItem;

/**
 * What Quick Help says in MxDataHUB (HubQuickHelp.swift): each screen in the sidebar, what's selected on it, and the
 * controls and panels the pointer can rest on. ⌘ reads Ctrl and ⌥ Alt; "this Mac" reads "this PC".
 */
final class HubHelp {
    private HubHelp() {}

    /** What a screen is for (the sidebar rows, and the screen itself when connected). */
    static QuickHelpItem about(Section section) {
        String detail = switch (section) {
            case CONNECTION -> "The PostgreSQL server and database that hold the security master (schema srm): host, port, user, database, password and SSL mode — the same screen as MxDataSQL's. "
                    + "The fields are remembered; the password only in secure storage. Once connected, the Database drop-down in the toolbar switches databases.";
            case SECURITIES -> "Search the security master by name or any identifier — ticker, TICKER:MIC, ISIN, CUSIP, SEDOL, FIGI, CIK or LEI — and filter by type, benchmark and active only. "
                    + "Select a security for its prices, identifiers with their history, issuer, listings, classification, benchmark weights and corporate actions.";
            case PORTFOLIOS -> "Portfolios and their dated holdings snapshots. Add holdings by any identifier as shares or a target weight, paste a list, or copy a benchmark's weights. "
                    + "Each snapshot is valued with the latest prices, checked (weights add up, every holding priced) and compared with the portfolio's benchmark.";
            case BENCHMARKS -> "Loaded benchmarks: constituents and weights, sector weights and the index level. Build custom benchmarks from another's members, "
                    + "see which benchmarks the data sources can supply, and track one with a portfolio in MxJobsMGR.";
            case REFRESH -> "Every category of market data in the security master: how fresh it is, when it was last loaded, and Refresh to run its srm.py loaders now through "
                    + "the SRM loader service on this PC (127.0.0.1:8770). Refresh All runs them in dependency order; Preview only shows the commands. "
                    + "Automate in MxJobsMGR… opens the scheduled, checked and approved SRM Daily Refresh. "
                    + "Activity, on the right, streams what the loaders print as they run — from here or from MxJobsMGR — with warnings and errors marked.";
            case LOADS -> "Where the data came from: how many securities have each identifier and from which sources, the data sources with their plan limits, "
                    + "the dated benchmark and portfolio weight snapshots attribution uses (a gap over a month shows in orange), and every load run with its row counts.";
            case SERVICES -> "Workflows: the MxJobsMGR samples built on this data — portfolio workflows open for the portfolio selected in Portfolios. "
                    + "Layer 3 Services: the shared services (Services/) that refresh and analyse the security master, what each is for and how to start it.";
        };
        return new QuickHelpItem(section.title, detail, section.shortcut());
    }

    /** The screen shown: what it's for, or why it's empty (not connected, no srm schema). */
    static QuickHelpItem screen(Section section, Hub hub) {
        if (section.needsConnection() && !hub.isConnected()) {
            return new QuickHelpItem(section.title + ": Not Connected",
                    section.title + " reads the security master, so it needs a connection to its database. "
                            + "Set it up in DB Connection (Ctrl+1) and Connect (Ctrl+Shift+K).", "Ctrl+Shift+K");
        }
        if (section.needsConnection() && !Boolean.TRUE.equals(hub.available)) {
            return new QuickHelpItem(section.title + ": No Security Master",
                    "Database " + (hub.connected == null ? "" : hub.connected.database()) + " has no srm schema. Pick the database that has it in the Database drop-down, "
                            + "or create it with SRM/srm.py migrate and load it with SRM/srm.py load all.");
        }
        return about(section);
    }

    // MARK: Securities

    static final QuickHelpItem SEARCH = new QuickHelpItem("Security Search",
            "Type a name or any identifier — AAPL, AAPL:XNAS, US0378331005, 037833100, 2046251, BBG000B9XRY4, a CIK or LEI — and press Enter. "
                    + "Type, In (benchmark) and Active only narrow the results, 1,000 to a page.", "Enter");
    static final QuickHelpItem RESULTS = new QuickHelpItem("Results",
            "The matching securities with their main identifiers, sector and MIC, 1,000 to a page (Previous / Next below). "
                    + "Click a column header to sort the column or filter it by values or a condition, as in Excel; the database does the work, so it covers every page. "
                    + "Select a security to see everything about it on the right.");
    static final QuickHelpItem SECURITY_DETAIL = new QuickHelpItem("Security Detail",
            "Prices, then identifiers with their history (From / To; grey ones are past values), issuer and security facts, listings, classification, "
                    + "benchmark membership with weights, and corporate actions.");

    // MARK: Portfolios

    static final QuickHelpItem PORTFOLIO_LIST = new QuickHelpItem("Portfolios",
            "Your portfolios with their benchmarks. Select one for its holdings; double-click to edit its code, name, currency and benchmark.");
    static final QuickHelpItem HOLDINGS = new QuickHelpItem("Holdings",
            "The snapshot's holdings: what you entered and how (ticker, ISIN…), shares, target weight, the latest price, market value and weight. "
                    + "Select rows and Remove to take them out. A problem shows under the table: weights that don't add up, an unpriced holding.");
    static final QuickHelpItem ACTIVE_WEIGHTS = new QuickHelpItem("vs Benchmark",
            "Each security's weight in the portfolio and in the benchmark and the difference (active weight), largest first, "
                    + "with the overlap and active share (1 − overlap).");
    static final QuickHelpItem TAX_LOTS = new QuickHelpItem("Tax Lots",
            "Open tax lots: shares bought on a date at a cost, valued with the latest price, with the unrealised gain and whether a sale today would be long-term (held over a year).");

    // MARK: Benchmarks

    static final QuickHelpItem BENCHMARK_LIST = new QuickHelpItem("Benchmarks",
            "The benchmarks loaded, with their members and the date of their latest constituents. Select one for its weights on the right.");
    static final QuickHelpItem CONSTITUENTS = new QuickHelpItem("Constituents",
            "The benchmark's members on its latest date with their sector, weight and shares. Type in Filter constituents to find a ticker, name or sector.");

    // MARK: Services & Workflows

    static final QuickHelpItem SERVICES_TABS = new QuickHelpItem("Workflows / Layer 3 Services",
            "Workflows lists the MxJobsMGR samples built on this data; Layer 3 Services lists the shared services they call.");

    static final QuickHelpItem STATUS = new QuickHelpItem("Status",
            "The connection — server, database and PostgreSQL version, and whether it has the security master — and the last action's message.");

    static final QuickHelpItem BACK = new QuickHelpItem("Back",
            "Back to the place before, as in a browser: the screen you came from, with the security, portfolio or benchmark it showed. "
                    + "Click the arrow beside the button for the places before it; Go ▸ History lists them too. Also Alt+Left, or a mouse's back button.", "Ctrl+[");
    static final QuickHelpItem FORWARD = new QuickHelpItem("Forward",
            "Forward again to where you went Back from. Click the arrow beside the button for the places after it. Also Alt+Right, or a mouse's forward button.", "Ctrl+]");
}
