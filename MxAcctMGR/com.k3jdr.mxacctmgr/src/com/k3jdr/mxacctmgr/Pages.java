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
            default -> new NotYetPage(app);
        };
    }
}
