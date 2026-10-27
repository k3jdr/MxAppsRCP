package com.k3jdr.mxjobsmgr;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mx.core.ui.WindowState;

/** MxJobsMGR's window: Design the day-to-day financial market data operation as a three-layer swim-lane diagram saved as JSON jobspace files, with API subscriptions, checks, and a run engine with approvals, breakpoints and mock or live Layer 3 calls. */
public class MainPart {
    private static MainPart instance;
    private Shell shell;

    @PostConstruct
    public void create(Composite parent) {
        instance = this;
        shell = parent.getShell();
        WindowState.track(shell, LifeCycle.APP);
        parent.setLayout(Ui.grid(1, 0, 0));

        Label title = Ui.label(parent, "MxJobsMGR", Ui.heading());
        title.setLayoutData(Ui.fillH());
        Ui.wrap(parent, "Design the day-to-day financial market data operation as a three-layer swim-lane diagram saved as JSON jobspace files, with API subscriptions, checks, and a run engine with approvals, breakpoints and mock or live Layer 3 calls.");
        Composite filler = new Composite(parent, SWT.NONE);
        filler.setLayoutData(Ui.fill());
        QuickHelp.get().setScreen(() -> new QuickHelpItem("MxJobsMGR", "Design the day-to-day financial market data operation as a three-layer swim-lane diagram saved as JSON jobspace files, with API subscriptions, checks, and a run engine with approvals, breakpoints and mock or live Layer 3 calls."));
        QuickHelp.get().createDockedPanel(parent);

        MenuBarBuilder mb = new MenuBarBuilder(shell);
        mb.menu("&File").item("E&xit", SWT.MOD3 | SWT.F4, shell::close);
        QuickHelp.get().addMenuItems(mb.menu("&View"));
        mb.install();
    }

    /** Arguments of a later launch (a link or a document), handed over by the running copy. */
    public static void handleArguments(List<String> args) {
        if (instance == null || instance.shell.isDisposed()) return;
        instance.shell.forceActive();
    }
}
