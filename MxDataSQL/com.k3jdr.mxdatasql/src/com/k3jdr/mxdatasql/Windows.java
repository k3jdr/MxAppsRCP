package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;

/**
 * MxDataSQL's windows (workspaces and table windows), so Quick Help can tell about the screen and the selection of the
 * window in front (the shared panel has one screen and one selection for the whole app).
 */
final class Windows {
    private Windows() {}

    interface HelpWindow {
        Shell shell();
        QuickHelpItem screen();
        QuickHelpItem selection();
    }

    private static final List<HelpWindow> ALL = new ArrayList<>();
    private static HelpWindow last;

    static void register(HelpWindow w) {
        if (ALL.isEmpty()) {
            QuickHelp.get().setScreen(() -> {
                HelpWindow a = active();
                return a == null ? null : a.screen();
            });
            QuickHelp.get().setSelection(() -> {
                HelpWindow a = active();
                return a == null ? null : a.selection();
            });
        }
        ALL.add(w);
        w.shell().addListener(org.eclipse.swt.SWT.Activate, e -> last = w);
        w.shell().addListener(org.eclipse.swt.SWT.Dispose, e -> {
            ALL.remove(w);
            if (last == w) last = null;
        });
        last = w;
    }

    /** The window in front, or the one last in front (when a dialog or the floating panel is). */
    static HelpWindow active() {
        Display d = Display.getCurrent();
        Shell s = d == null ? null : d.getActiveShell();
        for (Shell at = s; at != null; at = at.getParent() instanceof Shell p ? p : null) {
            for (HelpWindow w : ALL) if (w.shell() == at) return w;
        }
        return last;
    }

    static List<HelpWindow> all() { return List.copyOf(ALL); }
}
