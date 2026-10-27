package com.k3jdr.mxfilemgr.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Compare Files by Content (Ctrl+Alt+D; DiffView.swift): a line diff of two text files — lines removed from the
 * left file red, lines added in the right one green — or, for binary files, a byte-for-byte verdict. Xcode's
 * FileMerge has no Windows counterpart, so "Open both in Editor" hands the files to the editor instead.
 */
public final class DiffWindow {
    private static final Color REMOVED = new Color(new RGB(253, 226, 226));
    private static final Color ADDED = new Color(new RGB(222, 245, 222));
    private static final Color RED_TEXT = new Color(new RGB(190, 30, 30));

    private final Workspace ws;
    private final Path left, right;
    private final Shell shell;
    private final Composite body;
    private final StackLayout stack = new StackLayout();
    private final Label message;
    private final StyledText text;
    private final Label counts;
    private final Button onlyChanges;
    private List<Differ.Line> lines = List.of();

    private DiffWindow(Workspace ws, Path left, Path right) {
        this.ws = ws;
        this.left = left;
        this.right = right;
        shell = ToolWindows.create(ws, "diff", FileOps.name(left) + " ↔ " + FileOps.name(right), 900, 620, () -> FileHelp.DIFF,
                () -> new QuickHelpItem(FileOps.name(left) + " ↔ " + FileOps.name(right), left + "\n" + right));
        shell.setLayout(Ui.grid(1, 0, 0));

        Composite header = new Composite(shell, SWT.NONE);
        header.setLayout(Ui.grid(2, 8, 4));
        header.setLayoutData(Ui.fillH());
        Text l = Ui.selectable(header, "− " + left, SWT.SINGLE);
        l.setForeground(RED_TEXT);
        l.setLayoutData(Ui.fillH());
        QuickHelp.help(l, "Left File", "The left file: its lines missing on the right are red and marked −.");
        Text r = Ui.selectable(header, "+ " + right, SWT.SINGLE);
        r.setForeground(Ui.GREEN);
        r.setLayoutData(Ui.fillH());
        QuickHelp.help(r, "Right File", "The right file: its lines missing on the left are green and marked +.");

        Composite options = new Composite(shell, SWT.NONE);
        options.setLayout(Ui.grid(4, 8, 10));
        options.setLayoutData(Ui.fillH());
        onlyChanges = new Button(options, SWT.CHECK);
        onlyChanges.setText("Only changes");
        onlyChanges.addListener(SWT.Selection, e -> show());
        QuickHelp.help(onlyChanges, "Only changes", "Hides the lines both files have, leaving the removed and added ones.");
        counts = new Label(options, SWT.NONE);
        counts.setForeground(Ui.SECONDARY);
        counts.setLayoutData(Ui.fillH());
        QuickHelp.help(counts, "Changes", "How many lines were removed from the left file and added in the right one.");
        new Label(options, SWT.NONE);
        Button editor = Ui.button(options, "Open both in Editor", () -> ws.openInEditor(List.of(left, right)));
        QuickHelp.help(editor, "Open both in Editor", "Opens both files in the editor chosen in Settings (F4's editor), to merge changes by hand.");

        body = new Composite(shell, SWT.BORDER);
        body.setLayoutData(Ui.fill());
        body.setLayout(stack);
        message = new Label(body, SWT.CENTER | SWT.WRAP);
        message.setText("Comparing…");
        QuickHelp.help(message, "Compare Files", "What the comparison found.");
        text = new StyledText(body, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        text.setFont(Ui.mono());
        QuickHelp.help(text, FileHelp.DIFF.title(), FileHelp.DIFF.detail(), FileHelp.DIFF.shortcut());
        stack.topControl = message;
        shell.open();

        Background.run(shell, () -> Differ.diff(left, right), result -> {
            lines = result.lines();
            if (result.lines().isEmpty() || result.message() != null && result.message().endsWith("identical.")) {
                message.setText("\n\n" + (result.message() == null ? "The files are identical." : result.message()));
                stack.topControl = message;
                body.layout();
                updateCounts();
                return;
            }
            show();
        });
    }

    public static void open(Workspace ws, Path left, Path right) {
        new DiffWindow(ws, left, right);
    }

    private void updateCounts() {
        int removed = 0, added = 0;
        for (Differ.Line l : lines) {
            if (l.kind() == Differ.Kind.REMOVED) removed++;
            else if (l.kind() == Differ.Kind.ADDED) added++;
        }
        counts.setText(removed + " removed, " + added + " added");
        counts.getParent().layout();
    }

    private void show() {
        if (lines.isEmpty()) return;
        updateCounts();
        List<Differ.Line> shown = new ArrayList<>();
        for (Differ.Line l : lines) if (!onlyChanges.getSelection() || l.kind() != Differ.Kind.SAME) shown.add(l);
        int maxNo = 1;
        for (Differ.Line l : lines) maxNo = Math.max(maxNo, Math.max(l.left(), l.right()));
        int w = String.valueOf(maxNo).length();
        String fmt = "%" + w + "s %" + w + "s %s %s";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown.size(); i++) {
            Differ.Line l = shown.get(i);
            String sign = l.kind() == Differ.Kind.REMOVED ? "-" : l.kind() == Differ.Kind.ADDED ? "+" : " ";
            sb.append(String.format(fmt, l.left() == 0 ? "" : l.left(), l.right() == 0 ? "" : l.right(), sign, l.text()));
            if (i < shown.size() - 1) sb.append('\n');
        }
        text.setRedraw(false);
        text.setText(sb.toString());
        for (int i = 0; i < shown.size(); i++) {
            Differ.Kind k = shown.get(i).kind();
            if (k == Differ.Kind.REMOVED) text.setLineBackground(i, 1, REMOVED);
            else if (k == Differ.Kind.ADDED) text.setLineBackground(i, 1, ADDED);
        }
        text.setRedraw(true);
        stack.topControl = text;
        body.layout();
    }
}
