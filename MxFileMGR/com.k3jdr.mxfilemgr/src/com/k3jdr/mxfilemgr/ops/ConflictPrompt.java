package com.k3jdr.mxfilemgr.ops;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;

/**
 * Asks what to do with an item that already exists (FileOperations.swift's ConflictPrompt): both items' sizes and
 * dates, Replace / Skip / Keep Both / Stop, and Apply to all. Called from a job's worker thread; the dialog runs on
 * the UI thread while the worker waits.
 */
public final class ConflictPrompt {
    private ConflictPrompt() {}

    public record Answer(ConflictPolicy.Decision decision, boolean applyToAll) {}

    public static Answer ask(Path source, Path destination) {
        Display display = Display.getDefault();
        AtomicReference<Answer> answer = new AtomicReference<>(new Answer(ConflictPolicy.Decision.CANCEL, false));
        if (display.isDisposed()) return answer.get();
        display.syncExec(() -> {
            Shell parent = display.getActiveShell();
            if (parent == null && display.getShells().length > 0) parent = display.getShells()[0];
            String folder = destination.getParent() == null ? "" : FileOps.name(destination.getParent());
            String message = "“" + FileOps.name(destination) + "” already exists in “" + folder + "”.\n\n"
                    + "Existing:\t" + describe(destination) + "\nNew:\t" + describe(source);
            boolean[] all = new boolean[1];
            MessageDialog dialog = new MessageDialog(parent, "Item Exists", null, message, MessageDialog.WARNING, 0,
                    "Replace", "Skip", "Keep Both", "Stop") {
                @Override
                protected Control createCustomArea(Composite area) {
                    Button b = new Button(area, SWT.CHECK);
                    b.setText("Apply to all");
                    b.addListener(SWT.Selection, e -> all[0] = b.getSelection());
                    return b;
                }
            };
            int r = dialog.open();
            ConflictPolicy.Decision d = switch (r) {
                case 0 -> ConflictPolicy.Decision.OVERWRITE;
                case 1 -> ConflictPolicy.Decision.SKIP;
                case 2 -> ConflictPolicy.Decision.KEEP_BOTH;
                default -> ConflictPolicy.Decision.CANCEL;
            };
            answer.set(new Answer(d, all[0] && d != ConflictPolicy.Decision.CANCEL));
        });
        return answer.get();
    }

    private static String describe(Path p) {
        if (FileOps.isRealDirectory(p)) return "folder, modified " + Fmt.date(FileOps.modified(p));
        return Fmt.size(FileOps.size(p)) + ", modified " + Fmt.date(FileOps.modified(p));
    }
}
