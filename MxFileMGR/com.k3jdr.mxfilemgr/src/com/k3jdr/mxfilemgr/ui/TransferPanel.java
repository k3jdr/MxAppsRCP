package com.k3jdr.mxfilemgr.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.ops.TransferJob;
import com.k3jdr.mxfilemgr.ops.TransferManager;

/**
 * Running and recently finished jobs (MainView.swift's TransferPanel): title, the item being worked on, a progress
 * bar and Stop; finished jobs with problems keep a "N problems" link that shows them, until dismissed.
 */
public final class TransferPanel {
    private final Composite root;
    private final List<Row> rows = new ArrayList<>();

    private final class Row {
        final TransferJob job;
        final Composite box;
        final Label detail, state;
        final ProgressBar bar, spinner;
        final Button stop, dismiss;
        final Link problems;
        Text errors;
        TransferJob.State shown;

        Row(TransferJob job) {
            this.job = job;
            box = new Composite(root, SWT.NONE);
            box.setLayout(Ui.grid(6, 8, 4));
            box.setLayoutData(Ui.fillH());
            Ui.label(box, job.title, Ui.bold());
            detail = new Label(box, SWT.NONE);
            detail.setForeground(Ui.SECONDARY);
            detail.setLayoutData(Ui.fillH());
            problems = new Link(box, SWT.NONE);
            problems.addListener(SWT.Selection, e -> toggleErrors());
            state = new Label(box, SWT.NONE);
            state.setForeground(Ui.SECONDARY);
            stop = new Button(box, SWT.PUSH | SWT.FLAT);
            stop.setText("Stop");
            stop.addListener(SWT.Selection, e -> job.cancel());
            dismiss = new Button(box, SWT.PUSH | SWT.FLAT);
            dismiss.setText("✕");
            dismiss.setToolTipText("Dismiss");
            dismiss.addListener(SWT.Selection, e -> TransferManager.get().dismiss(job));
            bar = new ProgressBar(box, SWT.HORIZONTAL | SWT.SMOOTH);
            bar.setMaximum(1000);
            bar.setLayoutData(Ui.span(6));
            spinner = new ProgressBar(box, SWT.HORIZONTAL | SWT.INDETERMINATE);
            spinner.setLayoutData(Ui.span(6));
            QuickHelp.help(stop, "Stop", "Stops this job; what was done stays done.");
            QuickHelp.help(dismiss, "Dismiss", "Removes the finished job from the list.");
            QuickHelp.help(problems, "Problems", "Shows or hides what went wrong in this job.");
            for (Control c : box.getChildren()) if (c.getData(QuickHelp.KEY) == null) QuickHelp.help(c, FileHelp.TRANSFERS.title(), FileHelp.TRANSFERS.detail());
            QuickHelp.help(box, FileHelp.TRANSFERS.title(), FileHelp.TRANSFERS.detail());
            refresh();
        }

        void toggleErrors() {
            if (errors != null) {
                errors.dispose();
                errors = null;
            } else {
                errors = new Text(box, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.WRAP | SWT.BORDER);
                errors.setForeground(Ui.RED);
                errors.setFont(Ui.mono());
                errors.setText(String.join("\r\n", job.errors()));
                GridData gd = Ui.span(6);
                gd.heightHint = 80;
                errors.setLayoutData(gd);
            }
            layoutAll();
        }

        void refresh() {
            if (box.isDisposed()) return;
            String d = job.detail();
            if (!d.equals(detail.getText())) detail.setText(d);
            boolean running = job.state() == TransferJob.State.RUNNING;
            double f = job.fraction();
            if (running) {
                Ui.setVisible(bar, f >= 0);
                Ui.setVisible(spinner, f < 0);
                if (f >= 0) bar.setSelection((int) (f * 1000));
            }
            if (job.state() != shown) {
                shown = job.state();
                Ui.setVisible(stop, running);
                Ui.setVisible(dismiss, !running);
                if (!running) {
                    Ui.setVisible(bar, false);
                    Ui.setVisible(spinner, false);
                    state.setText(job.state() == TransferJob.State.CANCELLED ? "Stopped" : "Done");
                }
                layoutAll();
            }
            int n = job.errors().size();
            String p = n == 0 ? "" : "<a>" + Fmt.count(n, "problem") + "</a>";
            if (!p.equals(problems.getText())) {
                problems.setText(p);
                if (errors != null) errors.setText(String.join("\r\n", job.errors()));
                box.layout();
            }
        }
    }

    public TransferPanel(Composite parent) {
        root = new Composite(parent, SWT.BORDER);
        root.setLayout(Ui.grid(1, 0, 0));
        GridData gd = Ui.fillH();
        root.setLayoutData(gd);
        QuickHelp.help(root, FileHelp.TRANSFERS.title(), FileHelp.TRANSFERS.detail());
        TransferManager.get().addJobListener(() -> Ui.async(root, this::sync));
        sync();
        tick();
    }

    private void tick() {
        if (root.isDisposed()) return;
        for (Row r : rows) r.refresh();
        root.getDisplay().timerExec(150, this::tick);
    }

    private void sync() {
        List<TransferJob> jobs = TransferManager.get().jobs();
        rows.removeIf(r -> {
            if (!jobs.contains(r.job)) {
                r.box.dispose();
                return true;
            }
            return false;
        });
        for (TransferJob j : jobs) {
            if (rows.stream().noneMatch(r -> r.job == j)) rows.add(new Row(j));
        }
        for (Row r : rows) r.refresh();
        Ui.setVisible(root, !rows.isEmpty());
        layoutAll();
    }

    private void layoutAll() {
        if (root.isDisposed()) return;
        root.layout(true, true);
        root.getParent().layout(true, true);
    }
}
