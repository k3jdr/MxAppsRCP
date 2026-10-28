package com.k3jdr.mxdatasql;

import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;

/**
 * MxDataSQL's windows (workspaces and table windows) tell Quick Help about their own screen and selection; the shared
 * panel says what the window in front is showing.
 */
final class Windows {
    private Windows() {}

    interface HelpWindow {
        Shell shell();
        QuickHelpItem screen();
        QuickHelpItem selection();
    }

    static void register(HelpWindow w) {
        QuickHelp.get().setScreen(w.shell(), w::screen);
        QuickHelp.get().setSelection(w.shell(), w::selection);
    }
}
