package com.k3jdr.mxdatasql;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.IWindowCloseHandler;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.WindowState;

/**
 * MxDataSQL's main window: the first workspace (WorkspaceWindow). Closing it quits the app, after every open workspace
 * has asked to save its changes.
 */
public class MainPart {
    private static MainPart instance;
    private Shell shell;
    private WorkspaceWindow workspace;

    @PostConstruct
    public void create(Composite parent, MWindow window) {
        instance = this;
        shell = parent.getShell();
        WindowState.track(shell, LifeCycle.APP);
        workspace = new WorkspaceWindow(shell, parent, true, window::setLabel);
        window.getContext().set(IWindowCloseHandler.class, w -> WorkspaceWindow.confirmQuit());
        String[] args = Platform.getApplicationArgs();
        if (args != null && args.length > 0) {
            shell.getDisplay().asyncExec(() -> WorkspaceWindow.openArguments(shell.getDisplay(), List.of(args)));
        }
    }

    /** Arguments of a later launch (a workspace or a .dbml file), handed over by the running copy. */
    public static void handleArguments(List<String> args) {
        if (instance == null || instance.shell.isDisposed()) return;
        instance.shell.forceActive();
        WorkspaceWindow.openArguments(instance.shell.getDisplay(), args);
    }

    WorkspaceWindow workspace() { return workspace; }
}
