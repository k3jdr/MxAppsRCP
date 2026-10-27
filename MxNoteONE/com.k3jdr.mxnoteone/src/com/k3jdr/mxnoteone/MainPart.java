package com.k3jdr.mxnoteone;

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

/** MxNoteONE's window: A digital notebook in the spirit of Microsoft OneNote: notebooks, section groups, sections and pages, free-form page canvases with rich text containers, tables, pictures, tags, search and a recycle bin. */
public class MainPart {
    private static MainPart instance;
    private Shell shell;

    @PostConstruct
    public void create(Composite parent) {
        instance = this;
        shell = parent.getShell();
        WindowState.track(shell, LifeCycle.APP);
        parent.setLayout(Ui.grid(1, 0, 0));

        Label title = Ui.label(parent, "MxNoteONE", Ui.heading());
        title.setLayoutData(Ui.fillH());
        Ui.wrap(parent, "A digital notebook in the spirit of Microsoft OneNote: notebooks, section groups, sections and pages, free-form page canvases with rich text containers, tables, pictures, tags, search and a recycle bin.");
        Composite filler = new Composite(parent, SWT.NONE);
        filler.setLayoutData(Ui.fill());
        QuickHelp.get().setScreen(() -> new QuickHelpItem("MxNoteONE", "A digital notebook in the spirit of Microsoft OneNote: notebooks, section groups, sections and pages, free-form page canvases with rich text containers, tables, pictures, tags, search and a recycle bin."));
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
