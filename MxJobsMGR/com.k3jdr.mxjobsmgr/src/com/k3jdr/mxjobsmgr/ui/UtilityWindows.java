package com.k3jdr.mxjobsmgr.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.services.ServiceCatalog;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.ServicesView;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.MainPart;
import com.k3jdr.mxjobsmgr.engine.ProviderGuide;
import com.k3jdr.mxjobsmgr.model.Samples;

/** The utility windows: New from Sample, Layer 3 Services, and Help ▸ Adding API Keys. */
public final class UtilityWindows {
    private UtilityWindows() {}

    private static Shell samples, services, keys;
    private static ServicesView servicesView;
    private static Table keysList;
    private static ScrolledComposite keysScroller;

    private static Shell reuse(Shell s) {
        if (s != null && !s.isDisposed()) {
            if (s.getMinimized()) s.setMinimized(false);
            s.setActive();
            return s;
        }
        return null;
    }

    private static Shell window(Shell owner, String title, int w, int h) {
        Shell s = new Shell(owner.getDisplay(), SWT.SHELL_TRIM);
        s.setText(title);
        s.setImages(owner.getImages());
        s.setSize(w, h);
        var b = owner.getBounds();
        s.setLocation(b.x + Math.max(20, (b.width - w) / 2), b.y + Math.max(20, (b.height - h) / 3));
        return s;
    }

    // MARK: New from Sample

    public static void showSamples(MainPart main, Shell owner) {
        if (reuse(samples) != null) return;
        Shell s = window(owner, "New from Sample", 860, 640);
        samples = s;
        s.setLayout(Ui.grid(1, 16, 10));
        QuickHelp.help(s, JobsHelp.SAMPLES.title(), JobsHelp.SAMPLES.detail(), JobsHelp.SAMPLES.shortcut());
        Ui.label(s, "New from Sample", Ui.heading());
        Ui.wrap(s, "Each sample is a complete three-layer workflow. Portfolio samples use " + Samples.DEFAULT_PORTFOLIO
                + "; open them from a portfolio in MxDataHUB to use another one.").setForeground(Ui.SECONDARY);
        ScrolledComposite sc = new ScrolledComposite(s, SWT.V_SCROLL | SWT.BORDER);
        sc.setLayoutData(Ui.fill());
        sc.setExpandHorizontal(true);
        sc.setExpandVertical(true);
        Composite content = new Composite(sc, SWT.NONE);
        sc.setContent(content);
        Map<String, Boolean> running = new HashMap<>();
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            for (Control c : content.getChildren()) c.dispose();
            content.setLayout(Ui.grid(2, 12, 10));
            for (ServiceCatalog.Sample sample : ServiceCatalog.SAMPLES) {
                Composite text = new Composite(content, SWT.NONE);
                text.setLayout(Ui.grid(1, 0, 3));
                text.setLayoutData(Ui.fillH());
                Ui.label(text, sample.area().toUpperCase(), Ui.font(-1, SWT.BOLD)).setForeground(Ui.SECONDARY);
                Ui.label(text, sample.title(), Ui.subheading());
                Ui.wrap(text, sample.summary());
                StringBuilder needs = new StringBuilder("Needs: ");
                boolean allUp = true;
                for (String id : sample.services()) {
                    var svc = ServiceCatalog.service(id);
                    Boolean up = running.get(id);
                    if (!Boolean.TRUE.equals(up)) allUp = false;
                    needs.append(up == null ? "◌ " : up ? "● " : "○ ").append(svc.map(ServiceCatalog.Service::name).orElse(id)).append("   ");
                }
                Label n = Ui.label(text, needs.toString().trim() + (running.isEmpty() ? "" : allUp ? "  — all running" : "  — start them with: " + ServiceCatalog.startCommand()));
                n.setFont(Ui.small());
                n.setForeground(running.isEmpty() ? Ui.SECONDARY : allUp ? Ui.GREEN : Ui.ORANGE);
                Button create = Ui.button(content, "Create", () -> main.openSample(sample.id(), null, null));
                create.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
                QuickHelp.help(create, "Create", "Opens “" + sample.title() + "” as a new jobspace.");
                Label sep = new Label(content, SWT.SEPARATOR | SWT.HORIZONTAL);
                sep.setLayoutData(Ui.span(2));
            }
            sc.setMinSize(content.computeSize(Math.max(400, sc.getClientArea().width), SWT.DEFAULT));
            content.layout(true, true);
        };
        fill[0].run();
        sc.addListener(SWT.Resize, e -> sc.setMinSize(content.computeSize(Math.max(400, sc.getClientArea().width), SWT.DEFAULT)));
        Background.run(content, ServiceCatalog::probeAll, states -> {
            running.putAll(states);
            fill[0].run();
        }, err -> { });
        s.open();
    }

    // MARK: Layer 3 Services

    public static void showServices(MainPart main, Shell owner, String serviceId) {
        if (reuse(services) != null) {
            if (serviceId != null && servicesView != null && !servicesView.isDisposed()) servicesView.select(serviceId);
            return;
        }
        Shell s = window(owner, "Layer 3 Services", 1040, 720);
        services = s;
        s.setLayout(Ui.grid(1, 0, 0));
        QuickHelp.help(s, JobsHelp.SERVICES.title(), JobsHelp.SERVICES.detail(), JobsHelp.SERVICES.shortcut());
        Composite bar = new Composite(s, SWT.NONE);
        bar.setLayout(Ui.grid(4, 10, 8));
        bar.setLayoutData(Ui.fillH());
        bar.setBackground(Ui.SIDEBAR);
        Label which = Ui.label(bar, "Add a step calling:");
        which.setBackground(Ui.SIDEBAR);
        Combo endpoints = new Combo(bar, SWT.READ_ONLY);
        endpoints.setLayoutData(Ui.fillH());
        Button add = new Button(bar, SWT.PUSH);
        add.setText("Add Step");
        Label hint = Ui.label(bar, "");
        hint.setBackground(Ui.SIDEBAR);
        servicesView = new ServicesView(s, "New Jobspace", sample -> main.openSample(sample.id(), null, null));
        servicesView.setLayoutData(Ui.fill());
        // The shared ServicesView has no Add Step of its own: follow its list's selection.
        Table table = findTable(servicesView);
        ServiceCatalog.Service[] current = new ServiceCatalog.Service[1];
        Runnable sync = () -> {
            int i = table == null ? 0 : Math.max(0, table.getSelectionIndex());
            ServiceCatalog.Service svc = table != null && table.getItemCount() > i && table.getItem(i).getData() instanceof ServiceCatalog.Service x ? x : ServiceCatalog.ALL.get(0);
            current[0] = svc;
            endpoints.removeAll();
            for (ServiceCatalog.Endpoint ep : svc.endpoints()) endpoints.add(ep.method() + " " + ep.path() + " — " + ep.summary());
            endpoints.select(0);
            add.setEnabled(main.hasEditor());
            hint.setText(main.hasEditor() ? "" : "Open a jobspace first");
            bar.layout(true);
        };
        if (table != null) table.addListener(SWT.Selection, e -> sync.run());
        add.addListener(SWT.Selection, e -> {
            ServiceCatalog.Service svc = current[0];
            if (svc == null) return;
            int i = endpoints.getSelectionIndex();
            ServiceCatalog.Endpoint ep = i >= 0 && i < svc.endpoints().size() ? svc.endpoints().get(i) : null;
            main.withEditor(ed -> ed.addServiceStep(svc, ep));
        });
        s.addListener(SWT.Activate, e -> sync.run());
        QuickHelp.help(add, "Add Step", "Adds a Layer 3 step calling the chosen endpoint to the front jobspace, with the service's subscription.");
        QuickHelp.help(endpoints, "Endpoint", "The endpoint of the selected service the new step calls.");
        if (serviceId != null) servicesView.select(serviceId);
        sync.run();
        s.open();
    }

    private static Table findTable(Control c) {
        if (c instanceof Table t) return t;
        if (c instanceof Composite comp) {
            for (Control child : comp.getChildren()) {
                Table t = findTable(child);
                if (t != null) return t;
            }
        }
        return null;
    }

    // MARK: Adding API Keys

    public static void showKeysHelp(Shell owner, String providerId) {
        if (reuse(keys) == null) {
            Shell s = window(owner, "Adding API Keys", 820, 560);
            keys = s;
            s.setLayout(new FillLayout());
            QuickHelp.help(s, JobsHelp.KEYS.title(), JobsHelp.KEYS.detail());
            SplitPanes split = new SplitPanes(s, "jobs.keys", 180, 320, 210, 400);
            split.leading().setLayout(new FillLayout());
            keysList = new Table(split.leading(), SWT.SINGLE | SWT.FULL_SELECTION);
            new TableColumn(keysList, SWT.LEFT).setWidth(200);
            TableItem overview = new TableItem(keysList, SWT.NONE);
            overview.setText("🔑 Adding a key");
            overview.setData("overview");
            for (ProviderGuide g : allGuides()) {
                TableItem it = new TableItem(keysList, SWT.NONE);
                it.setText("   " + g.name());
                it.setData(g.id());
            }
            split.trailing().setLayout(new FillLayout());
            keysScroller = new ScrolledComposite(split.trailing(), SWT.V_SCROLL);
            keysScroller.setExpandHorizontal(true);
            keysScroller.setExpandVertical(true);
            keysList.addListener(SWT.Selection, e -> showGuide((String) keysList.getSelection()[0].getData()));
            s.open();
        }
        String id = providerId == null ? "overview" : providerId;
        for (int i = 0; i < keysList.getItemCount(); i++) if (id.equals(keysList.getItem(i).getData())) keysList.setSelection(i);
        showGuide(id);
    }

    private static List<ProviderGuide> allGuides() {
        List<ProviderGuide> all = new java.util.ArrayList<>(ProviderGuide.ALL);
        all.add(ProviderGuide.GENERIC);
        return all;
    }

    private static void showGuide(String id) {
        if (keysScroller == null || keysScroller.isDisposed()) return;
        if (keysScroller.getContent() != null) keysScroller.getContent().dispose();
        Composite c = new Composite(keysScroller, SWT.NONE);
        c.setLayout(Ui.grid(1, 24, 8));
        keysScroller.setContent(c);
        if (id.equals("overview")) {
            Ui.label(c, "Adding API Keys", Ui.heading());
            para(c, "Layer 3 API Data Read steps call data services through API subscriptions. Services that need a key get it from the subscription.");
            String[] steps = {
                    "Open API Subscriptions — the 🔑 button above the canvas, Diagram ▸ API Subscriptions… (Ctrl+Alt+K), or click a “no secure account” problem.",
                    "Select the subscription. Under API Key, press ? for that provider's steps and Get a Key… to sign up.",
                    "Choose its Secure account, or press New in MxAcctMGR… to make one there.",
                    "In MxAcctMGR ▸ Banking ▸ Secure Accounts, put the key in the account's Service key (Edit Service Details; Open in MxAcctMGR goes straight to it).",
                    "Back here, press Test Key: MxJobsMGR calls the provider once and says whether the key was accepted.",
                    "Run the jobspace with Layer 3 set to Live, or use Test Call on a step." };
            for (int i = 0; i < steps.length; i++) para(c, (i + 1) + ".  " + steps[i]);
            para(c, "🔒 Keys are kept by MxAcctMGR ▸ Secure Accounts in Eclipse secure storage (encrypted for your Windows user), never in the jobspace file — "
                    + "share a jobspace without its keys. MxJobsMGR only reads them; it has no place to enter one.").setForeground(Ui.SECONDARY);
            para(c, "Deleting a subscription leaves its secure account and key in MxAcctMGR.").setForeground(Ui.SECONDARY);
        } else {
            ProviderGuide g = allGuides().stream().filter(x -> x.id().equals(id)).findFirst().orElse(ProviderGuide.GENERIC);
            Composite head = new Composite(c, SWT.NONE);
            head.setLayout(Ui.grid(2, 0, 10));
            Ui.label(head, g.name(), Ui.heading());
            Label need = Ui.label(head, g.needsKey() ? "Key required" : "No key needed");
            need.setForeground(g.needsKey() ? Ui.ORANGE : Ui.GREEN);
            for (int i = 0; i < g.steps().size(); i++) para(c, (i + 1) + ".  " + g.steps().get(i));
            if (g.needsKey() && !g.id().equals("generic"))
                para(c, "🔑 Authentication: " + g.auth().title + (g.keyName().isEmpty() ? "" : " — " + g.keyName()));
            para(c, "⏱ " + g.limits());
            for (String note : g.notes()) para(c, "ⓘ " + note).setForeground(Ui.SECONDARY);
            Composite links = new Composite(c, SWT.NONE);
            links.setLayout(Ui.grid(2, 0, 8));
            if (g.signupURL() != null) Ui.button(links, "Get a Key…", () -> Program.launch(g.signupURL()));
            if (g.docsURL() != null) Ui.button(links, "Documentation", () -> Program.launch(g.docsURL()));
        }
        keysScroller.setMinSize(c.computeSize(Math.max(300, keysScroller.getClientArea().width), SWT.DEFAULT));
        keysScroller.addListener(SWT.Resize, e -> {
            if (!c.isDisposed()) keysScroller.setMinSize(c.computeSize(Math.max(300, keysScroller.getClientArea().width), SWT.DEFAULT));
        });
        c.layout(true, true);
    }

    private static Text para(Composite c, String text) {
        Text t = Ui.selectable(c, text, SWT.NONE);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.widthHint = 300;
        t.setLayoutData(gd);
        return t;
    }
}
