package com.k3jdr.mxjobsmgr;

import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.lifecycle.PostContextCreate;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Display;

import com.k3jdr.mx.core.links.SingleInstance;
import com.k3jdr.mx.core.ui.WindowState;

/** Starts MxJobsMGR: one running copy (a second launch hands over its arguments), and the window where it was. */
public class LifeCycle {
    public static final String APP = "MxJobsMGR";

    @PostContextCreate
    void postContextCreate() {
        if (!SingleInstance.claim(APP, Platform.getApplicationArgs(),
                args -> Display.getDefault().asyncExec(() -> MainPart.handleArguments(args)))) {
            System.exit(0);
        }
    }

    @ProcessAdditions
    void processAdditions(MApplication app, EModelService models) {
        if (models.find("com.k3jdr.mxjobsmgr.window", app) instanceof MWindow window) WindowState.restore(window, APP);
    }
}
