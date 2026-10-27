package com.k3jdr.mxjobsmgr.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.engine.RunEngine;

/**
 * The question a run waits on (RunViews.swift RunPromptBanner): approve or reject an approval, send or cancel an
 * execution request (nothing is ever sent), retry / skip / abort a failure, run or skip Python scripts — with the
 * details (what is being approved, the code) to read first. Shown above the canvas while the run waits.
 */
public final class PromptBanner {
    private final Editor editor;
    private final Composite root;
    private RunEngine.Prompt shown;
    private boolean showDetail;

    public PromptBanner(Composite parent, Editor editor) {
        this.editor = editor;
        root = new Composite(parent, SWT.NONE);
        GridData gd = Ui.fillH();
        root.setLayoutData(gd);
        Ui.setVisible(root, false);
    }

    public void update() {
        if (root.isDisposed()) return;
        RunEngine.Prompt prompt = editor.run.prompt();
        if (prompt == shown) return;
        shown = prompt;
        showDetail = false;
        build();
    }

    private void build() {
        for (Control c : root.getChildren()) c.dispose();
        RunEngine.Prompt prompt = shown;
        Ui.setVisible(root, prompt != null);
        if (prompt != null) {
            root.setLayout(Ui.grid(2 + prompt.options().size(), 10, 6));
            root.setBackground(Colors.tint(0xFF9500, 0.12));
            Label icon = new Label(root, SWT.NONE);
            icon.setText("?");
            icon.setFont(Ui.font(8, SWT.BOLD));
            icon.setForeground(Colors.of(0xE08600));
            icon.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, false, false));
            Composite texts = new Composite(root, SWT.NONE);
            texts.setLayout(Ui.grid(1, 0, 2));
            texts.setLayoutData(Ui.fillH());
            Label title = Ui.label(texts, prompt.title(), Ui.subheading());
            title.setLayoutData(Ui.fillH());
            Text message = Ui.selectable(texts, prompt.message(), SWT.NONE);
            GridData mg = new GridData(SWT.FILL, SWT.TOP, true, false);
            mg.widthHint = 300;
            message.setLayoutData(mg);
            Button first = null;
            for (RunEngine.Option option : prompt.options()) {
                Button b = new Button(root, SWT.PUSH);
                b.setText(option.label());
                b.setLayoutData(new GridData(SWT.END, SWT.TOP, false, false));
                b.addListener(SWT.Selection, e -> editor.run.respond(option.label()));
                if (option.isDestructive()) b.setForeground(Colors.of(0xD03030));
                if (option.isDefault()) {
                    b.setFont(Ui.bold());
                    first = b;
                }
            }
            if (!prompt.detail().isEmpty()) {
                Button toggle = new Button(root, SWT.TOGGLE);
                toggle.setText(showDetail ? "▾ Details" : "▸ Details");
                toggle.setSelection(showDetail);
                GridData tg = new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2 + prompt.options().size(), 1);
                toggle.setLayoutData(tg);
                toggle.addListener(SWT.Selection, e -> {
                    showDetail = toggle.getSelection();
                    build();
                });
                if (showDetail) {
                    Text detail = new Text(root, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
                    detail.setText(prompt.detail());
                    detail.setFont(Ui.mono());
                    GridData dg = new GridData(SWT.FILL, SWT.FILL, true, false, 2 + prompt.options().size(), 1);
                    dg.heightHint = 130;
                    detail.setLayoutData(dg);
                }
            }
            Ui.background(root, Colors.tint(0xFF9500, 0.12));
            var help = JobsHelp.prompt(prompt);
            QuickHelp.help(root, help.title(), help.detail());
            if (first != null) first.setToolTipText("The suggested answer");
        }
        root.getParent().layout(true, true);
    }
}
