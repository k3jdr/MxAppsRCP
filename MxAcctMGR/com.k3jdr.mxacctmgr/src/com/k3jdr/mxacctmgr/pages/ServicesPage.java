package com.k3jdr.mxacctmgr.pages;

import org.eclipse.swt.widgets.Composite;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.ServicesView;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.text.Help;
import com.k3jdr.mxacctmgr.ui.Page;

/** Reporting ▸ Layer 3 Services: the shared ServicesView — what each service is for, whether it runs, how to start it. */
public final class ServicesPage extends Page {
    public ServicesPage(App app) {
        super(app);
    }

    @Override
    protected boolean fills() { return true; }

    @Override
    protected void build(Composite body) {
        ServicesView view = new ServicesView(body, null, null);
        view.setLayoutData(Ui.fill());
        QuickHelp.help(body, app.title(screen, true), Help.screen(screen.id));
    }
}
