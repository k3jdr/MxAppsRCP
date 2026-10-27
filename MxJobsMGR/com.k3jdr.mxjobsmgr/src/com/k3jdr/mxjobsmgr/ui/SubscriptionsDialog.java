package com.k3jdr.mxjobsmgr.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.links.AppLinks;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.engine.KeyTester;
import com.k3jdr.mxjobsmgr.engine.ProviderGuide;
import com.k3jdr.mxjobsmgr.engine.SecureAccounts;
import com.k3jdr.mxjobsmgr.engine.SecureKeys;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Specs.AuthStyle;
import com.k3jdr.mxjobsmgr.model.Specs.IdeChoice;
import com.k3jdr.mxjobsmgr.model.Specs.KeyValue;

/**
 * API Subscriptions (SubscriptionsDialog.swift + SecureAccounts.swift): the jobspace's subscriptions — base URL, plan,
 * rate limit, how the key is sent, headers sent with every call, notes, source project — and the MxAcctMGR secure
 * account whose service key each sends. The keys themselves are entered in MxAcctMGR ▸ Secure Accounts; MxJobsMGR has
 * no place to type one.
 */
public final class SubscriptionsDialog extends Dialog {
    private final Editor editor;
    private final Jobspace space;
    private String selection;
    private Table list;
    private ScrolledComposite scroller;
    private Composite form;
    private SecureAccounts.Listing accounts = SecureAccounts.last();
    private boolean testing;
    private KeyTester.Outcome testResult;
    private String message;

    public SubscriptionsDialog(Shell parent, Editor editor, String select) {
        super(parent);
        this.editor = editor;
        this.space = editor.space;
        this.selection = select != null ? select : space.subscriptions.isEmpty() ? null : space.subscriptions.get(0).id;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("API Subscriptions — " + space.name);
    }

    @Override
    protected Point getInitialSize() {
        return new Point(900, 720);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 8, 8));
        QuickHelp.help(area, JobsHelp.SUBSCRIPTIONS.title(), JobsHelp.SUBSCRIPTIONS.detail(), JobsHelp.SUBSCRIPTIONS.shortcut());

        Composite left = new Composite(area, SWT.NONE);
        left.setLayout(Ui.grid(1, 0, 4));
        GridData lg = new GridData(SWT.FILL, SWT.FILL, false, true);
        lg.widthHint = 230;
        left.setLayoutData(lg);
        list = new Table(left, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        list.setLayoutData(Ui.fill());
        new TableColumn(list, SWT.LEFT).setWidth(220);
        list.addListener(SWT.Selection, e -> {
            TableItem[] sel = list.getSelection();
            selection = sel.length == 0 ? null : (String) sel[0].getData();
            testResult = null;
            message = null;
            buildForm();
        });
        QuickHelp.help(list, "Subscriptions", "This jobspace's API subscriptions and whether each has a key in MxAcctMGR ▸ Secure Accounts. Select one to edit it; − deletes it (its key stays in Secure Accounts).");
        Composite buttons = new Composite(left, SWT.NONE);
        buttons.setLayout(Ui.grid(2, 0, 4));
        Button add = Ui.button(buttons, " + ", () -> { });
        add.addListener(SWT.Selection, e -> {
            Menu m = new Menu(add);
            MenuItem plain = new MenuItem(m, SWT.PUSH);
            plain.setText("New Subscription");
            plain.addListener(SWT.Selection, x -> add(new ApiSubscription()));
            MenuItem local = new MenuItem(m, SWT.PUSH);
            local.setText("Local Python Service (127.0.0.1:" + ApiSubscription.LOCAL_SERVICE_PORT + ")");
            local.addListener(SWT.Selection, x -> add(ApiSubscription.localService(ApiSubscription.LOCAL_SERVICE_PORT)));
            m.setLocation(add.toDisplay(0, add.getSize().y));
            m.setVisible(true);
        });
        QuickHelp.help(add, "Add Subscription", "A new subscription for a data service, or one for the local Python service (127.0.0.1:" + ApiSubscription.LOCAL_SERVICE_PORT + ").");
        Button del = Ui.button(buttons, " − ", this::deleteSelected);
        QuickHelp.help(del, "Delete Subscription", "Deletes the selected subscription; its steps are left without one. Its secure account and key stay in MxAcctMGR.");

        scroller = new ScrolledComposite(area, SWT.V_SCROLL | SWT.BORDER);
        scroller.setLayoutData(Ui.fill());
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        scroller.addListener(SWT.Resize, e -> relayout());

        Label note = Ui.label(area, "API keys are kept in MxAcctMGR ▸ Secure Accounts, never in the jobspace file.");
        note.setForeground(Ui.SECONDARY);
        note.setLayoutData(Ui.span(2));

        fillList();
        buildForm();
        Background.run(area, SecureAccounts::load, listing -> {
            accounts = listing;
            if (SecureAccounts.link(space, listing)) editor.changed();
            fillList();
            buildForm();
        }, err -> { });
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Done", true);
    }

    private void fillList() {
        if (list.isDisposed()) return;
        list.removeAll();
        for (ApiSubscription sub : space.subscriptions) {
            TableItem item = new TableItem(list, SWT.NONE);
            item.setData(sub.id);
            item.setText((sub.name.isEmpty() ? "Untitled" : sub.name) + "   " + Inspector.keyStatus(sub)[0]);
            if (sub.id.equals(selection)) list.setSelection(item);
        }
    }

    private void add(ApiSubscription sub) {
        editor.willChange(null);
        space.subscriptions.add(sub);
        editor.structureChanged();
        selection = sub.id;
        fillList();
        buildForm();
    }

    private int usage(String id) {
        int n = 0;
        for (JobNode node : space.nodes) {
            if ((node.api != null && id.equals(node.api.subscriptionID)) || (node.script != null && id.equals(node.script.subscriptionID))) n++;
        }
        return n;
    }

    private void deleteSelected() {
        ApiSubscription sub = space.subscription(selection);
        if (sub == null) return;
        int n = usage(sub.id);
        MessageBox box = new MessageBox(getShell(), SWT.ICON_WARNING | SWT.OK | SWT.CANCEL);
        box.setText("Delete this subscription?");
        box.setMessage(n > 0 ? n + " API read" + (n == 1 ? "" : "s") + " use it and will be left without a subscription. Its secure account and key stay in MxAcctMGR."
                : "Its secure account and key stay in MxAcctMGR.");
        if (box.open() != SWT.OK) return;
        editor.willChange(null);
        space.subscriptions.remove(sub);
        for (JobNode node : space.nodes) {
            if (node.api != null && sub.id.equals(node.api.subscriptionID)) node.api.subscriptionID = null;
            if (node.script != null && sub.id.equals(node.script.subscriptionID)) node.script.subscriptionID = null;
        }
        editor.structureChanged();
        selection = space.subscriptions.isEmpty() ? null : space.subscriptions.get(0).id;
        fillList();
        buildForm();
    }

    // MARK: Form

    private void relayout() {
        if (form == null || form.isDisposed()) return;
        scroller.setMinSize(form.computeSize(Math.max(400, scroller.getClientArea().width), SWT.DEFAULT));
        form.layout(true, true);
    }

    private void rebuildLater() {
        getShell().getDisplay().asyncExec(() -> {
            if (!scroller.isDisposed()) {
                fillList();
                buildForm();
            }
        });
    }

    private void edit(String key, Runnable change, boolean structural) {
        editor.willChange(key);
        change.run();
        editor.changed();
        if (structural) rebuildLater();
    }

    private Group section(String title) {
        Group g = new Group(form, SWT.NONE);
        g.setText(title);
        g.setLayout(Ui.grid(2, 8, 5));
        g.setLayoutData(Ui.fillH());
        QuickHelpItem help = JobsHelp.section(title);
        if (help != null) QuickHelp.help(g, help.title(), help.detail());
        return g;
    }

    private Text text(Composite g, String label, String value, String prompt, Consumer<String> set, boolean structural) {
        Ui.label(g, label);
        Text t = new Text(g, SWT.BORDER);
        t.setText(value);
        if (prompt != null) t.setMessage(prompt);
        t.setLayoutData(Ui.fillH());
        String key = "sub:" + selection + ":" + label;
        t.addListener(SWT.Modify, e -> edit(key, () -> set.accept(t.getText()), false));
        if (structural) t.addListener(SWT.FocusOut, e -> rebuildLater());
        return t;
    }

    private Label small(Composite g, String text, int color, int span) {
        Label l = new Label(g, SWT.WRAP);
        l.setText(Ui.escape(text));
        l.setForeground(Colors.of(color));
        l.setFont(Ui.small());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, span, 1);
        gd.widthHint = 200;
        l.setLayoutData(gd);
        return l;
    }

    private void buildForm() {
        if (scroller.isDisposed()) return;
        Point origin = scroller.getOrigin();
        if (form != null) form.dispose();
        form = new Composite(scroller, SWT.NONE);
        form.setLayout(Ui.grid(1, 10, 8));
        scroller.setContent(form);
        ApiSubscription sub = space.subscription(selection);
        if (sub == null) {
            Ui.label(form, "No Subscription Selected", Ui.subheading());
            Ui.wrap(form, "Add a subscription for each data service the Layer 3 API reads call.").setForeground(Ui.SECONDARY);
            relayout();
            return;
        }

        Group service = section("Service");
        text(service, "Name", sub.name, null, v -> {
            sub.name = v;
            for (TableItem it : list.getItems()) if (sub.id.equals(it.getData())) it.setText(v.isEmpty() ? "Untitled" : v);
        }, false);
        text(service, "Provider", sub.provider, null, v -> sub.provider = v, false);
        text(service, "Base URL", sub.baseURL, "https://api.example.com/v1", v -> sub.baseURL = v, true).setFont(Ui.mono());
        text(service, "Plan", sub.plan, "e.g. Free, Premium", v -> sub.plan = v, false);
        Ui.label(service, "Rate limit (calls / min)");
        Spinner rate = new Spinner(service, SWT.BORDER);
        rate.setValues(sub.rateLimitPerMinute, 0, 10_000, 0, 1, 10);
        rate.setToolTipText("0 = none set");
        rate.addListener(SWT.Modify, e -> edit("sub:" + sub.id + ":rate", () -> sub.rateLimitPerMinute = rate.getSelection(), false));
        Ui.label(service, "Documentation");
        Composite docs = new Composite(service, SWT.NONE);
        docs.setLayout(Ui.grid(2, 0, 4));
        docs.setLayoutData(Ui.fillH());
        Text docsText = new Text(docs, SWT.BORDER);
        docsText.setText(sub.docsURL);
        docsText.setMessage("https://");
        docsText.setLayoutData(Ui.fillH());
        docsText.addListener(SWT.Modify, e -> edit("sub:" + sub.id + ":docs", () -> sub.docsURL = docsText.getText(), false));
        Button openDocs = Ui.button(docs, "↗", () -> {
            if (ProviderGuide.host(sub.docsURL) != null) Program.launch(sub.docsURL.trim());
        });
        openDocs.setToolTipText("Open the documentation");
        int n = usage(sub.id);
        if (n > 0) {
            Ui.label(service, "Used by");
            Ui.label(service, n + " step" + (n == 1 ? "" : "s"));
        }

        keySection(sub);

        Group headers = section("Headers Sent With Every Call");
        for (KeyValue kv : sub.defaultHeaders) {
            Composite r = new Composite(headers, SWT.NONE);
            r.setLayout(Ui.grid(3, 0, 4));
            r.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            Text k = new Text(r, SWT.BORDER);
            k.setText(kv.key);
            k.setMessage("header");
            k.setLayoutData(Ui.fillH());
            k.addListener(SWT.Modify, e -> edit("hdr:" + kv.id + ":k", () -> kv.key = k.getText(), false));
            Text v = new Text(r, SWT.BORDER);
            v.setText(kv.value);
            v.setMessage("value");
            v.setLayoutData(Ui.fillH());
            v.addListener(SWT.Modify, e -> edit("hdr:" + kv.id + ":v", () -> kv.value = v.getText(), false));
            Ui.button(r, "−", () -> edit(null, () -> sub.defaultHeaders.remove(kv), true)).setToolTipText("Remove");
        }
        Button addHeader = Ui.button(headers, "+ Add Header", () -> edit(null, () -> sub.defaultHeaders.add(new KeyValue()), true));
        addHeader.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));

        Group project = section("Source Project");
        Ui.label(project, "Project folder");
        Composite pf = new Composite(project, SWT.NONE);
        pf.setLayout(Ui.grid(2, 0, 4));
        pf.setLayoutData(Ui.fillH());
        Text folder = new Text(pf, SWT.BORDER);
        folder.setText(sub.projectFolder);
        folder.setMessage("D:\\path\\to\\service");
        folder.setFont(Ui.mono());
        folder.setLayoutData(Ui.fillH());
        folder.addListener(SWT.Modify, e -> edit("sub:" + sub.id + ":folder", () -> sub.projectFolder = folder.getText(), false));
        folder.addListener(SWT.FocusOut, e -> rebuildLater());
        Ui.button(pf, "Choose…", () -> {
            DirectoryDialog dd = new DirectoryDialog(getShell());
            dd.setText("Choose the project folder");
            String start = sub.projectFolder.isBlank() ? null : ProjectOpener.expand(sub.projectFolder).toString();
            if (start != null) dd.setFilterPath(start);
            String chosen = dd.open();
            if (chosen != null) edit(null, () -> sub.projectFolder = chosen, true);
        });
        Ui.label(project, "Open with");
        Combo ide = new Combo(project, SWT.READ_ONLY);
        for (IdeChoice c : IdeChoice.values()) ide.add(c.title);
        ide.select(sub.ide.ordinal());
        ide.addListener(SWT.Selection, e -> edit(null, () -> sub.ide = IdeChoice.values()[ide.getSelectionIndex()], true));
        if (!sub.projectFolder.isBlank()) {
            Button open = Ui.button(project, "Open Project in " + sub.ide.title, () -> {
                message = ProjectOpener.open(sub.projectFolder, sub.ide);
                rebuildLater();
            });
            open.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
            open.setToolTipText(ProjectOpener.expand(sub.projectFolder).toString());
        }
        small(project, "For services you develop, such as Services/portfolio-java or Services/portfolio-cpp: steps using this subscription get an “Open Project” button.", Colors.SECONDARY, 2);

        Group notes = section("Notes");
        Text nt = new Text(notes, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        nt.setText(sub.notes);
        GridData ng = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        ng.heightHint = 70;
        ng.widthHint = 200;
        nt.setLayoutData(ng);
        nt.addListener(SWT.Modify, e -> edit("sub:" + sub.id + ":notes", () -> sub.notes = nt.getText(), false));

        relayout();
        scroller.setOrigin(origin);
    }

    private void keySection(ApiSubscription sub) {
        Group g = section("API Key");
        ProviderGuide guide = ProviderGuide.matching(sub);
        Composite head = new Composite(g, SWT.NONE);
        head.setLayout(Ui.grid(3, 0, 6));
        head.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Label gname = Ui.label(head, guide.name() + (guide.needsKey() ? " · key required" : " · no key needed"));
        gname.setForeground(Ui.SECONDARY);
        gname.setLayoutData(Ui.fillH());
        if (guide.signupURL() != null) Ui.button(head, "Get a Key…", () -> Program.launch(guide.signupURL()));
        Button help = Ui.button(head, " ? ", () -> editor.main.showKeysHelp(guide.id()));
        help.setToolTipText("How to get and add a key for " + guide.name());

        Ui.label(g, "Authentication");
        Combo auth = new Combo(g, SWT.READ_ONLY);
        for (AuthStyle a : AuthStyle.values()) auth.add(a.title);
        auth.select(sub.auth.ordinal());
        auth.setLayoutData(Ui.fillH());
        auth.addListener(SWT.Selection, e -> edit(null, () -> sub.auth = AuthStyle.values()[auth.getSelectionIndex()], true));
        if (sub.auth == AuthStyle.QUERY_PARAMETER || sub.auth == AuthStyle.HEADER) {
            text(g, sub.auth == AuthStyle.HEADER ? "Header name" : "Parameter name", sub.keyName, sub.auth == AuthStyle.HEADER ? "X-API-Key" : "apikey",
                    v -> sub.keyName = v, false);
        }
        if (sub.auth != AuthStyle.NONE) {
            if (sub.auth == AuthStyle.LOCAL_SERVICE)
                small(g, "Optional: choose a secure account only if the service requires a token (its service key is sent as “Authorization: Bearer …”).", Colors.SECONDARY, 2);
            accountPicker(g, sub);
        }
        if (!guide.id().equals("generic") && guide.auth() != sub.auth && (guide.needsKey() || sub.auth.requiresKey())) {
            small(g, "⚠ " + guide.name() + " expects: " + guide.auth().title + (guide.keyName().isEmpty() ? "" : " (" + guide.keyName() + ")"), 0xC77700, 1);
            Ui.button(g, "Use It", () -> edit(null, () -> {
                sub.auth = guide.auth();
                sub.keyName = guide.keyName();
            }, true));
        }
        if (guide.baseURL() != null && !ProviderGuide.sameBase(sub.baseURL, guide.baseURL())) {
            small(g, "⚠ " + guide.name() + "'s base URL is " + guide.baseURL() + " — endpoint paths and parameters go on each API Data Read step", 0xC77700, 1);
            Ui.button(g, "Use It", () -> edit(null, () -> sub.baseURL = guide.baseURL(), true));
        }
    }

    private void accountPicker(Group g, ApiSubscription sub) {
        Ui.label(g, "Secure account");
        Combo picker = new Combo(g, SWT.READ_ONLY);
        picker.setLayoutData(Ui.fillH());
        picker.setToolTipText("The account in MxAcctMGR ▸ Secure Accounts whose service key this subscription's calls send");
        List<Integer> ids = new ArrayList<>();
        picker.add("None");
        ids.add(null);
        List<SecureAccounts.Account> list = accounts == null ? List.of() : accounts.accounts();
        for (SecureAccounts.Account a : list) {
            if (!a.isActive() && !Integer.valueOf(a.id()).equals(sub.secureAccountID)) continue;
            picker.add(a.name() + (SecureKeys.hasKey(a.id()) ? "" : " — no service key"));
            ids.add(a.id());
        }
        if (sub.secureAccountID != null && !ids.contains(sub.secureAccountID)) {
            picker.add(sub.secureAccountName.isEmpty() ? "Secure account " + sub.secureAccountID : sub.secureAccountName);
            ids.add(sub.secureAccountID);
        }
        picker.select(Math.max(0, ids.indexOf(sub.secureAccountID)));
        picker.addListener(SWT.Selection, e -> edit(null, () -> {
            Integer id = ids.get(Math.max(0, picker.getSelectionIndex()));
            sub.secureAccountID = id;
            SecureAccounts.Account a = accounts == null ? null : accounts.account(id);
            if (a != null) sub.secureAccountName = a.name();
            else if (id == null) sub.secureAccountName = "";
            testResult = null;
        }, true));
        if (accounts == null) small(g, "Reading MxAcctMGR's secure accounts…", Colors.SECONDARY, 2);
        else if (accounts.problem() != null) small(g, accounts.problem() + " Only the secure accounts that already have a service key are listed.", Colors.SECONDARY, 2);

        Composite status = new Composite(g, SWT.NONE);
        status.setLayout(Ui.grid(3, 0, 6));
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Label s = new Label(status, SWT.WRAP);
        s.setLayoutData(Ui.fillH());
        boolean hasKey = SecureKeys.hasKey(sub);
        if (sub.secureAccountID == null) {
            s.setText(Ui.escape(sub.secureAccountName.isEmpty() ? "No secure account chosen" : "Links to “" + sub.secureAccountName + "” once it is in Secure Accounts"));
            s.setForeground(Ui.ORANGE);
        } else if (hasKey) {
            s.setText("🔑 Service key in Secure Accounts");
            s.setForeground(Ui.GREEN);
        } else {
            s.setText(Ui.escape("No service key in “" + sub.secureAccountName + "” yet"));
            s.setForeground(Ui.ORANGE);
        }
        if (hasKey) {
            Button test = Ui.button(status, "Test Key", () -> runKeyTest(sub));
            test.setEnabled(!testing);
            QuickHelp.help(test, "Test Key", "Calls the provider once with the service key and says whether it was accepted.");
        }
        if (sub.secureAccountID != null) {
            Integer id = sub.secureAccountID;
            Button open = Ui.button(status, "Open in MxAcctMGR", () -> openLink(new AppLink.SecureAccount(id)));
            open.setToolTipText("Show this secure account in MxAcctMGR ▸ Secure Accounts, where its service key is entered and changed");
        } else {
            Button make = Ui.button(status, "New in MxAcctMGR…", () -> {
                if (sub.secureAccountName.isEmpty()) edit(null, () -> sub.secureAccountName = sub.name, false);
                openLink(new AppLink.NewSecureAccount(sub.secureAccountName, sub.baseURL, sub.docsURL));
            });
            make.setToolTipText("Make a secure account for this subscription in MxAcctMGR ▸ Secure Accounts and put its key there; back here, the subscription links to it by name");
        }
        if (message != null) small(g, message, Colors.SECONDARY, 2);
        if (testing) small(g, "Testing the key…", Colors.SECONDARY, 2);
        else if (testResult != null) {
            int color = switch (testResult.verdict()) {
                case VALID -> 0x1E8C3C;
                case INVALID -> 0xC82828;
                case LIMITED, UNKNOWN -> 0xC77700;
                case UNREACHABLE -> Colors.SECONDARY;
            };
            Text t = Ui.selectable(g, testResult.message(), SWT.NONE);
            t.setForeground(Colors.of(color));
            GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            gd.widthHint = 200;
            t.setLayoutData(gd);
        }
    }

    private void openLink(AppLink link) {
        message = AppLinks.open(link) ? null : "MxAcctMGR is not installed: it keeps the API keys (Banking ▸ Secure Accounts).";
        rebuildLater();
    }

    private void runKeyTest(ApiSubscription sub) {
        testing = true;
        testResult = null;
        ApiSubscription copy = ApiSubscription.read(sub.toJson());
        buildForm();
        Background.run(scroller, () -> KeyTester.test(copy), r -> {
            testing = false;
            testResult = r;
            buildForm();
        }, err -> {
            testing = false;
            testResult = new KeyTester.Outcome(KeyTester.Verdict.UNKNOWN, String.valueOf(err.getMessage()));
            buildForm();
        });
    }
}
