package com.k3jdr.mxacctmgr.pages;

import org.eclipse.swt.widgets.Composite;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.ui.Page;
import com.k3jdr.mxacctmgr.ui.Parts;

/**
 * A screen of the macOS app not ported to Windows yet: its title, lesson and what it does (its Quick Help), so the
 * guide stays whole and Previous / Next walk through it.
 */
public final class NotYetPage extends Page {
    public NotYetPage(App app) {
        super(app);
    }

    @Override
    protected void build(Composite body) {
        Composite box = Parts.box(body, "Not on Windows yet", "This screen of the macOS app is not ported to Windows yet. PARITY.md lists what is.");
        Parts.text(box, Help.screen(screen.id));
        Parts.caption(box, "This screen of MxAcctMGR for macOS is not ported to Windows yet (see PARITY.md in the MxAcctMGR folder). The rest of the guide works: Previous and Next below walk past it.");
        QuickHelp.help(box.getParent(), app.title(screen, true), Help.screen(screen.id));
    }
}
