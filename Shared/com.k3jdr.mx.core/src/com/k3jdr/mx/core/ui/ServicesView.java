package com.k3jdr.mx.core.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.services.ServiceCatalog.Sample;
import com.k3jdr.mx.core.services.ServiceCatalog.Service;

/**
 * The Layer 3 services, as MxDataHUB's Services &amp; Workflows and MxJobsMGR's Layer 3 Services window show them
 * (MxAppsIOS/Shared/ServicesView.swift): each service's state, language and why, use cases, endpoints, the start
 * command and project folder, and the sample workflows built on it.
 */
public final class ServicesView extends Composite {
    private final Table table;
    private final Composite detailHost;
    private final ScrolledComposite scroller;
    private final Label summary;
    private final Map<String, Boolean> running = new HashMap<>();
    private final String openSampleTitle;
    private final Consumer<Sample> openSample;

    /**
     * @param openSampleTitle the label of the sample buttons ("Open in MxJobsMGR", "New Jobspace"), or null for none
     * @param openSample      what the sample buttons do
     */
    public ServicesView(Composite parent, String openSampleTitle, Consumer<Sample> openSample) {
        super(parent, SWT.NONE);
        this.openSampleTitle = openSampleTitle;
        this.openSample = openSample;
        setLayout(Ui.grid(1, 0, 0));
        SplitPanes split = new SplitPanes(this, "services", 260, 520, 330, 380);
        split.setLayoutData(Ui.fill());

        Composite left = split.leading();
        left.setLayout(Ui.grid(1, 8, 6));
        Composite bar = new Composite(left, SWT.NONE);
        bar.setLayout(Ui.grid(2, 0, 6));
        bar.setLayoutData(Ui.fillH());
        summary = Ui.label(bar, "Checking…");
        summary.setLayoutData(Ui.fillH());
        QuickHelp.help(Ui.button(bar, "Check", this::refresh), "Check",
                "Asks each service's /health whether it is running now.");
        table = new Table(left, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLayoutData(Ui.fill());
        for (String[] c : new String[][] { { "Service", "190" }, { "Language", "70" }, { "Port", "50" }, { "State", "80" } }) {
            TableColumn col = new TableColumn(table, SWT.LEFT);
            col.setText(c[0]);
            col.setWidth(Integer.parseInt(c[1]));
        }
        for (Service s : ServiceCatalog.ALL) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setData(s);
            item.setText(new String[] { s.name(), s.language(), Integer.toString(s.port()), "…" });
        }
        TableCopy.addMenuItems(table);
        QuickHelp.provider(table, (c, p) -> {
            TableItem item = table.getItem(p);
            if (item == null) return new QuickHelpItem("Layer 3 services", "The local services the workflows call. Select one for what it is for, its endpoints and how to start it.");
            Service s = (Service) item.getData();
            return new QuickHelpItem(s.name(), s.language() + " on port " + s.port() + ". " + s.purpose());
        });
        table.addListener(SWT.Selection, e -> showDetail());

        Label guide = Ui.label(left, "Choosing a language", Ui.bold());
        guide.setLayoutData(Ui.fillH());
        for (String[] g : ServiceCatalog.LANGUAGE_GUIDE) {
            Ui.label(left, g[0], Ui.bold());
            Ui.wrap(left, g[1]).setForeground(Ui.SECONDARY);
        }

        scroller = new ScrolledComposite(split.trailing(), SWT.V_SCROLL);
        split.trailing().setLayout(new org.eclipse.swt.layout.FillLayout());
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        detailHost = new Composite(scroller, SWT.NONE);
        scroller.setContent(detailHost);
        scroller.addListener(SWT.Resize, e -> resizeDetail());

        table.setSelection(0);
        showDetail();
        refresh();
    }

    /** Asks every service whether it runs, then updates the list. */
    public void refresh() {
        summary.setText("Checking…");
        Background.run(this, ServiceCatalog::probeAll, states -> {
            running.clear();
            running.putAll(states);
            long up = states.values().stream().filter(b -> b).count();
            summary.setText(up + " of " + ServiceCatalog.ALL.size() + " running");
            for (TableItem item : table.getItems()) {
                Service s = (Service) item.getData();
                boolean on = Boolean.TRUE.equals(running.get(s.id()));
                item.setText(3, on ? "Running" : "Stopped");
                item.setForeground(3, on ? Ui.GREEN : Ui.SECONDARY);
            }
            showDetail();
        });
    }

    public void select(String serviceId) {
        TableItem[] items = table.getItems();
        for (int i = 0; i < items.length; i++) {
            if (((Service) items[i].getData()).id().equals(serviceId)) {
                table.setSelection(i);
                showDetail();
            }
        }
    }

    private void showDetail() {
        for (Control c : detailHost.getChildren()) c.dispose();
        detailHost.setLayout(Ui.grid(1, 14, 6));
        int i = table.getSelectionIndex();
        if (i < 0) return;
        Service s = (Service) table.getItem(i).getData();
        Ui.label(detailHost, s.name(), Ui.subheading());
        Boolean on = running.get(s.id());
        Label state = Ui.label(detailHost, on == null ? "Checking…" : on ? "Running on :" + s.port() : "Not running");
        state.setForeground(Boolean.TRUE.equals(on) ? Ui.GREEN : Ui.SECONDARY);
        Ui.selectable(detailHost, s.purpose(), SWT.NONE).setLayoutData(wrapData());
        Ui.label(detailHost, "Why " + s.language(), Ui.bold());
        Ui.selectable(detailHost, s.whyThisLanguage(), SWT.NONE).setLayoutData(wrapData());

        section("Use it for");
        for (ServiceCatalog.UseCase u : s.cases()) {
            Ui.label(detailHost, u.title, Ui.bold());
            Ui.selectable(detailHost, u.whenToUse, SWT.NONE).setLayoutData(wrapData());
        }

        section("Endpoints");
        for (ServiceCatalog.Endpoint ep : s.endpoints()) {
            Composite row = new Composite(detailHost, SWT.NONE);
            row.setLayout(Ui.grid(2, 0, 8));
            row.setLayoutData(Ui.fillH());
            Label m = Ui.label(row, ep.method(), Ui.bold());
            m.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            Ui.selectable(row, ep.path() + "\n" + ep.summary() + (ep.contract() == null ? "" : " · " + ep.contract()), SWT.NONE)
                    .setLayoutData(wrapData());
        }

        if (!s.samples().isEmpty()) {
            section("Sample workflows using it");
            for (String id : s.samples()) {
                ServiceCatalog.sample(id).ifPresent(sample -> {
                    Ui.label(detailHost, sample.title(), Ui.bold());
                    Ui.selectable(detailHost, sample.summary(), SWT.NONE).setLayoutData(wrapData());
                    if (openSampleTitle != null && openSample != null) Ui.button(detailHost, openSampleTitle, () -> openSample.accept(sample));
                });
            }
        }

        section("Run and develop");
        Ui.selectable(detailHost, "Start all:  " + ServiceCatalog.startCommand() + "\nStatus:     " + ServiceCatalog.statusCommand()
                + "\nProject:    " + s.projectPath() + "\nDevelop in: " + s.developIn(), SWT.NONE).setFont(Ui.mono());
        Composite buttons = new Composite(detailHost, SWT.NONE);
        buttons.setLayout(Ui.grid(2, 0, 6));
        Ui.button(buttons, "Copy Start Command", () -> Clipboards.copyText(getDisplay(), ServiceCatalog.startCommand()));
        Ui.button(buttons, "Show Project Folder", () -> Program.launch(s.projectPath().toString()));
        resizeDetail();
    }

    private void section(String title) {
        Label sep = new Label(detailHost, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(Ui.fillH());
        Ui.label(detailHost, title, Ui.subheading());
    }

    private static GridData wrapData() {
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.widthHint = 300;
        return gd;
    }

    private void resizeDetail() {
        int width = scroller.getClientArea().width;
        scroller.setMinSize(detailHost.computeSize(Math.max(300, width), SWT.DEFAULT));
        detailHost.layout(true, true);
    }
}
