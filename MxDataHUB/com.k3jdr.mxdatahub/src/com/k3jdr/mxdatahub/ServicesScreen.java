package com.k3jdr.mxdatahub;

import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.services.ServiceCatalog.Sample;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.ServicesView;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.PortfolioRow;

/**
 * Services &amp; Workflows (ServicesWorkflowsView.swift): Workflows — the MxJobsMGR samples built on this data, with
 * the services each needs and whether they run; portfolio workflows open for the portfolio selected in Portfolios —
 * and Layer 3 Services (the core's ServicesView).
 */
final class ServicesScreen extends Composite implements Screen {
    private final Hub hub;
    private final Button workflowsTab, servicesTab;
    private final Composite body;
    private final StackLayout stack = new StackLayout();
    private final Composite gallery;
    private final Label note;
    private final ScrolledComposite scroller;
    private final Composite samples;
    private ServicesView services;
    private Map<String, Boolean> running = Map.of();

    ServicesScreen(Composite parent, Hub hub, MainPart main) {
        super(parent, SWT.NONE);
        this.hub = hub;
        setLayout(Ui.grid(1, 0, 0));
        Composite head = new Composite(this, SWT.NONE);
        head.setLayout(Ui.grid(4, 10, 6));
        head.setLayoutData(Ui.fillH());
        workflowsTab = new Button(head, SWT.TOGGLE);
        workflowsTab.setText("Workflows");
        servicesTab = new Button(head, SWT.TOGGLE);
        servicesTab.setText("Layer 3 Services");
        for (Button b : new Button[] { workflowsTab, servicesTab }) QuickHelp.help(b, HubHelp.SERVICES_TABS.title(), HubHelp.SERVICES_TABS.detail());
        new Label(head, SWT.NONE).setLayoutData(Ui.fillH());
        Label caption = Ui.label(head, "MxDataHUB keeps the data; MxJobsMGR runs the workflows over it.");
        caption.setForeground(Ui.SECONDARY);
        caption.setFont(Ui.small());
        new Label(this, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());

        body = new Composite(this, SWT.NONE);
        body.setLayoutData(Ui.fill());
        body.setLayout(stack);

        // Workflows: the sample gallery.
        gallery = new Composite(body, SWT.NONE);
        gallery.setLayout(Ui.grid(1, 0, 0));
        note = Ui.wrap(gallery, "");
        ((GridData) note.getLayoutData()).horizontalIndent = 14;
        ((GridData) note.getLayoutData()).verticalIndent = 10;
        scroller = new ScrolledComposite(gallery, SWT.V_SCROLL);
        scroller.setLayoutData(Ui.fill());
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        samples = new Composite(scroller, SWT.NONE);
        samples.setLayout(Ui.grid(1, 14, 6));
        scroller.setContent(samples);
        scroller.addListener(SWT.Resize, e -> resize());
        new Label(gallery, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
        Composite foot = new Composite(gallery, SWT.NONE);
        foot.setLayout(Ui.grid(3, 10, 8));
        foot.setLayoutData(Ui.fillH());
        Label live = Ui.label(foot, "Services must be running for Live runs; Mock runs work without them.");
        live.setForeground(Ui.SECONDARY);
        live.setFont(Ui.small());
        live.setLayoutData(Ui.fillH());
        Text command = new Text(foot, SWT.READ_ONLY | SWT.BORDER);
        command.setText(ServiceCatalog.startCommand());
        command.setFont(Ui.mono());
        GridData cg = new GridData(SWT.FILL, SWT.CENTER, false, false);
        cg.widthHint = 380;
        command.setLayoutData(cg);
        QuickHelp.help(command, "Start All", "The command that starts every Layer 3 service (Services/mxservices.py).");
        Ui.button(foot, "Copy", () -> Clipboards.copyText(getDisplay(), ServiceCatalog.startCommand()));

        workflowsTab.addListener(SWT.Selection, e -> showTab(false));
        servicesTab.addListener(SWT.Selection, e -> showTab(true));
        // Launch argument -servicesTab Services opens the services list.
        showTab("Services".equalsIgnoreCase(main.launchArgument("servicesTab")));
        buildSamples();
        probe();
    }

    private void showTab(boolean servicesList) {
        workflowsTab.setSelection(!servicesList);
        servicesTab.setSelection(servicesList);
        if (servicesList && services == null) services = new ServicesView(body, "Open in MxJobsMGR", this::open);
        stack.topControl = servicesList ? services : gallery;
        body.layout(true, true);
    }

    private String noteText() {
        PortfolioRow p = hub.selectedPortfolio();
        if (p != null) {
            return "Portfolio workflows open for " + p.code() + (p.benchmarkCode().isEmpty() ? "" : " against " + p.benchmarkCode())
                    + " — the portfolio selected in Portfolios.";
        }
        return "Select a portfolio in Portfolios to open the portfolio workflows for it (otherwise they use DEMO_GROWTH).";
    }

    private void open(Sample sample) {
        PortfolioRow p = sample.takesPortfolio() ? hub.selectedPortfolio() : null;
        if (!AppLinks.open(new AppLink.Sample(sample.id(), p == null ? null : p.code(), p == null ? null : p.benchmarkCode())))
            hub.error("MxJobsMGR is not installed: install it beside MxDataHUB, or register its mxjobsmgr:// links.");
    }

    private void probe() {
        Background.run(this, ServiceCatalog::probeAll, states -> {
            running = states;
            buildSamples();
        }, t -> { });
    }

    private void buildSamples() {
        note.setText(Ui.escape(noteText()));
        for (Control c : samples.getChildren()) c.dispose();
        for (String area : List.of("Data", "Active", "Passive", "Analytics", "Templates")) {
            List<Sample> list = ServiceCatalog.SAMPLES.stream().filter(s -> s.area().equals(area)).toList();
            if (list.isEmpty()) continue;
            Label h = Ui.label(samples, area, Ui.bold());
            h.setForeground(Ui.SECONDARY);
            for (Sample s : list) row(s);
        }
        gallery.layout(true, true);
        resize();
    }

    private void row(Sample s) {
        Composite row = new Composite(samples, SWT.NONE);
        row.setLayout(Ui.grid(2, 4, 4));
        row.setLayoutData(Ui.fillH());
        Composite text = new Composite(row, SWT.NONE);
        text.setLayout(Ui.grid(1, 0, 4));
        text.setLayoutData(Ui.fillH());
        Ui.label(text, s.title(), Ui.bold());
        Label summary = Ui.wrap(text, s.summary());
        summary.setForeground(Ui.SECONDARY);
        Composite chips = new Composite(text, SWT.NONE);
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.marginLeft = rl.marginTop = rl.marginBottom = 0;
        rl.spacing = 8;
        chips.setLayout(rl);
        for (String id : s.services()) {
            ServiceCatalog.service(id).ifPresent(svc -> {
                boolean on = Boolean.TRUE.equals(running.get(id));
                Label chip = Ui.label(chips, (on ? "● " : "○ ") + svc.name() + " (" + svc.language() + ")");
                chip.setFont(Ui.small());
                chip.setForeground(on ? Ui.GREEN : Ui.SECONDARY);
                chip.setToolTipText(svc.name() + (on ? " is running on :" + svc.port() : " is not running"));
            });
        }
        Button open = Ui.button(row, "Open in MxJobsMGR", () -> open(s));
        open.setLayoutData(new GridData(SWT.END, SWT.TOP, false, false));
        String uses = String.join(", ", s.services().stream().flatMap(id -> ServiceCatalog.service(id).stream()).map(ServiceCatalog.Service::name).toList());
        String detail = s.summary() + "\nUses " + (uses.isEmpty() ? "no services" : uses) + ". Open in MxJobsMGR opens it"
                + (s.takesPortfolio() ? " for a portfolio (DEMO_GROWTH unless it comes from a portfolio in MxDataHUB)." : ".");
        for (Control c : new Control[] { row, text, summary, chips, open }) QuickHelp.help(c, s.area() + ": " + s.title(), detail);
    }

    private void resize() {
        int width = scroller.getClientArea().width;
        if (width <= 0) return;
        scroller.setMinSize(samples.computeSize(width, SWT.DEFAULT));
        samples.layout(true, true);
    }

    @Override
    public void shown() {
        note.setText(Ui.escape(noteText()));
        gallery.layout(true, true);
        probe();
        if (services != null && stack.topControl == services) services.refresh();
    }

    @Override
    public void hubChanged() {
        if (!isDisposed() && isVisible()) {
            String t = Ui.escape(noteText());
            if (!t.equals(note.getText())) {
                note.setText(t);
                gallery.layout(true, true);
            }
        }
    }

    @Override
    public Control control() { return this; }
}
