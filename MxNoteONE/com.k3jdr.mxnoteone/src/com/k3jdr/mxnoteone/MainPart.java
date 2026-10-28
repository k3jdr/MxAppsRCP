package com.k3jdr.mxnoteone;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import jakarta.annotation.PostConstruct;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.WindowState;
import com.k3jdr.mxnoteone.model.TableData;
import com.k3jdr.mxnoteone.store.Disk;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.table.Formula;
import com.k3jdr.mxnoteone.ui.Gfx;
import com.k3jdr.mxnoteone.ui.MainWindow;

/**
 * MxNoteONE's window: a digital notebook in the spirit of Microsoft OneNote — notebooks, section groups,
 * sections and pages; free-form pages of rich text containers, tables, pictures and attachments; tags, search and a
 * recycle bin. Notebooks are folders in the Mac app's format, so the same notebook opens on either system.
 *
 * <p>Launch with {@code -notebooks <folder>} to keep notebooks in another folder (tests use one under %TEMP%).
 */
public class MainPart {
    private static MainPart instance;
    private Shell shell;
    private MainWindow window;

    @PostConstruct
    public void create(Composite parent) {
        instance = this;
        shell = parent.getShell();
        Display display = shell.getDisplay();
        Gfx.init(display);
        TableData.formulaShifter = Formula::shift;
        WindowState.track(shell, LifeCycle.APP);
        NotebookStore store = NotebookStore.get();
        List<String> args = Arrays.asList(Platform.getApplicationArgs());
        int at = args.indexOf("-notebooks");
        if (at >= 0 && at + 1 < args.size()) store.setLibrary(Path.of(args.get(at + 1)));
        Disk.errors = (what, why) -> display.asyncExec(() -> ErrorDialog.show(shell, what, why));
        store.alerts = m -> display.asyncExec(() -> MessageDialog.openInformation(shell, m[0], m[1]));
        window = new MainWindow(parent);
        shell.addListener(SWT.Close, e -> {
            window.page.flush();
            Disk.drain();
        });
        store.loadLibrary(display, () -> {
            window.ready();
            handleArguments(args);
            if (args.contains("-selftest") && at >= 0) window.selfTest();
        });
    }

    /** Arguments of a later launch (an mxnoteone:// link), handed over by the running copy. */
    public static void handleArguments(List<String> args) {
        if (instance == null || instance.shell.isDisposed()) return;
        instance.shell.forceActive();
        for (String a : args) {
            if (a.toLowerCase(Locale.ROOT).startsWith(NotebookStore.LINK_SCHEME + "://")) {
                instance.window.page.flush();
                NotebookStore.get().openLink(a);
            }
        }
    }
}
