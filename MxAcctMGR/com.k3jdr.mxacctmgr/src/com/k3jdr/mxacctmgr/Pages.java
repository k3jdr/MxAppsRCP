package com.k3jdr.mxacctmgr;

import com.k3jdr.mxacctmgr.pages.NotYetPage;
import com.k3jdr.mxacctmgr.pages.ServicesPage;
import com.k3jdr.mxacctmgr.ui.Page;

/** The page for each screen (ContentView.swift's {@code screen} switch). */
final class Pages {
    private Pages() {}

    static Page create(App app, Screen s) {
        return switch (s) {
            case SERVICES -> new ServicesPage(app);
            case CHECKING -> new com.k3jdr.mxacctmgr.bank.CheckingPage(app);
            case EXPENSES -> new com.k3jdr.mxacctmgr.bank.TargetAccountsPage(app);
            case SECURE_ACCOUNTS -> new com.k3jdr.mxacctmgr.bank.SecureAccountsPage(app);
            case EXPENSE_LEDGERS -> new com.k3jdr.mxacctmgr.bank.ExpenseLedgersPage(app);
            case MY_PORTFOLIO, MY_BENCHMARK -> new com.k3jdr.mxacctmgr.invest.HoldingsPage(app);
            case MY_RISK -> new com.k3jdr.mxacctmgr.invest.RiskPage(app);
            case MY_IMPROVE -> new com.k3jdr.mxacctmgr.invest.ImprovePage(app);
            case MY_TRADES -> new com.k3jdr.mxacctmgr.invest.TradesPage(app);
            case MY_ATTRIBUTION -> new com.k3jdr.mxacctmgr.invest.AttributionPage(app);
            case WELCOME, CHOOSE_PATH, WHY_OPTIMIZE, WHY_ATTRIBUTION, ACTIVE_VS_PASSIVE -> new com.k3jdr.mxacctmgr.pages.LearnPage(app);
            case REPORT_LIBRARY -> new com.k3jdr.mxacctmgr.pages.LibraryPages.ReportLibrary(app);
            case SAMPLES -> new com.k3jdr.mxacctmgr.pages.LibraryPages.Samples(app);
            case REPORT_WALKTHROUGH -> new com.k3jdr.mxacctmgr.pages.LibraryPages.Walkthrough(app);
            case SECURITIES -> new com.k3jdr.mxacctmgr.pages.SecuritiesPage(app);
            default -> new NotYetPage(app);
        };
    }
}
