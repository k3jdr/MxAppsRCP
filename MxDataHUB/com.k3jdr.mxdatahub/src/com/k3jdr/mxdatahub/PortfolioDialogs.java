package com.k3jdr.mxdatahub;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Db.HoldingEntry;
import com.k3jdr.mxdatahub.Rows.BenchmarkRow;
import com.k3jdr.mxdatahub.Rows.PortfolioRow;
import com.k3jdr.mxdatahub.Rows.ResolveMatch;

/** The Portfolios screen's dialogs (SRMPortfoliosView.swift). */
final class PortfolioDialogs {
    private PortfolioDialogs() {}

    /** A dialog with its problems in red under the fields, and the default button enabled only without them. */
    abstract static class Checked extends Dialog {
        protected Label problems;

        Checked(Shell parent) {
            super(parent);
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }

        protected Label problemsLabel(Composite parent, int span) {
            problems = new Label(parent, SWT.WRAP);
            problems.setForeground(Ui.RED);
            GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, span, 1);
            gd.widthHint = 380;
            problems.setLayoutData(gd);
            return problems;
        }

        abstract List<String> problems();

        protected void check() {
            if (problems == null || problems.isDisposed()) return;
            List<String> p = problems();
            problems.setText(Ui.escape(String.join("\n", p)));
            Button ok = getButton(IDialogConstants.OK_ID);
            if (ok != null) ok.setEnabled(p.isEmpty() && okAllowed());
            problems.getParent().layout(true);
            getShell().layout(true, true);
        }

        protected boolean okAllowed() { return true; }
    }

    // MARK: New / Edit portfolio

    static final class PortfolioDialog extends Checked {
        private final Hub hub;
        private final PortfolioRow existing;
        private Text code, name, currency, description;
        private Combo benchmark;
        private List<BenchmarkRow> benchmarks;
        String codeValue, nameValue, currencyValue, descriptionValue;
        Integer benchmarkValue;

        PortfolioDialog(Shell parent, Hub hub, PortfolioRow existing) {
            super(parent);
            this.hub = hub;
            this.existing = existing;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText(existing == null ? "New Portfolio" : "Edit Portfolio");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(Ui.grid(2, 14, 8));
            Label head = Ui.label(area, existing == null ? "New Portfolio" : "Edit Portfolio", Ui.subheading());
            head.setLayoutData(Ui.span(2));
            Ui.label(area, "Code");
            code = text(area, "GROWTH1");
            Ui.label(area, "Name");
            name = text(area, "Growth portfolio");
            Ui.label(area, "Base currency");
            currency = text(area, "USD");
            currency.setTextLimit(3);
            currency.addListener(SWT.Verify, e -> e.text = e.text.toUpperCase());
            Ui.label(area, "Benchmark");
            benchmark = new Combo(area, SWT.READ_ONLY);
            benchmark.setLayoutData(Ui.fillH());
            benchmarks = hub.benchmarks;
            benchmark.add("None");
            for (BenchmarkRow b : benchmarks) benchmark.add(b.code() + " — " + b.name());
            Label dl = Ui.label(area, "Description");
            dl.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            description = new Text(area, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            GridData dg = new GridData(SWT.FILL, SWT.FILL, true, true);
            dg.heightHint = 60;
            description.setLayoutData(dg);
            problemsLabel(area, 2);
            QuickHelp.help(code, "Code", "1–40 letters, digits, - or _; MxJobsMGR and the services name the portfolio by it.");
            QuickHelp.help(benchmark, "Benchmark", "The benchmark the portfolio is compared with (vs Benchmark) and optimized or tracked against in MxJobsMGR.");

            if (existing != null) {
                code.setText(existing.code());
                name.setText(existing.name());
                currency.setText(existing.currency());
                description.setText(existing.description());
                select(existing.benchmarkId());
            } else {
                currency.setText("USD");
                benchmarks.stream().filter(b -> b.code().equals("SP500")).findFirst().ifPresentOrElse(b -> select(b.id()), () -> benchmark.select(0));
            }
            for (Text t : new Text[] { code, name, currency }) t.addListener(SWT.Modify, e -> check());
            return area;
        }

        private void select(Integer id) {
            benchmark.select(0);
            for (int i = 0; i < benchmarks.size(); i++) if (id != null && benchmarks.get(i).id() == id) benchmark.select(i + 1);
        }

        private static Text text(Composite parent, String prompt) {
            Text t = new Text(parent, SWT.BORDER);
            t.setMessage(prompt);
            GridData gd = Ui.fillH();
            gd.widthHint = 280;
            t.setLayoutData(gd);
            return t;
        }

        @Override
        List<String> problems() {
            List<String> out = new ArrayList<>();
            String c = code.getText();
            if (!c.matches("[A-Za-z0-9_\\-]{1,40}")) out.add("Code: 1–40 letters, digits, - or _");
            if (hub.portfolios.stream().anyMatch(p -> p.code().equalsIgnoreCase(c) && (existing == null || p.id() != existing.id())))
                out.add("Code " + c + " is already used");
            if (name.getText().trim().isEmpty()) out.add("Enter a name");
            if (!currency.getText().matches("[A-Z]{3}")) out.add("Currency: ISO 4217 code, e.g. USD");
            return out;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            createButton(parent, IDialogConstants.OK_ID, existing == null ? "Create" : "Save", true);
            check();
        }

        @Override
        protected void okPressed() {
            if (!problems().isEmpty()) return;
            codeValue = code.getText();
            nameValue = name.getText();
            currencyValue = currency.getText();
            descriptionValue = description.getText();
            int i = benchmark.getSelectionIndex();
            benchmarkValue = i <= 0 ? null : benchmarks.get(i - 1).id();
            super.okPressed();
        }
    }

    // MARK: Add holding

    static final class AddHoldingDialog extends Checked {
        private final Hub hub;
        private final String heading;
        private final BiConsumer<List<HoldingEntry>, Consumer<List<String>>> add;
        private Text identifier, amount;
        private Combo type;
        private Button shares, weight;
        private Composite matchesBox;
        private Label failuresLabel;
        private List<ResolveMatch> matches = List.of();
        private boolean searched;
        private int generation;
        private List<String> failures = List.of();

        AddHoldingDialog(Shell parent, Hub hub, String heading, BiConsumer<List<HoldingEntry>, Consumer<List<String>>> add) {
            super(parent);
            this.hub = hub;
            this.heading = heading;
            this.add = add;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Add Holding");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(Ui.grid(2, 14, 8));
            Label head = Ui.label(area, heading, Ui.subheading());
            head.setLayoutData(Ui.span(2));
            Ui.label(area, "Identifier");
            identifier = new Text(area, SWT.BORDER);
            identifier.setMessage("AAPL, US0378331005, 037833100, 2046251, BBG000B9XRY4, AAPL:XNAS");
            identifier.setLayoutData(Ui.fillH());
            QuickHelp.help(identifier, "Identifier", "Any identifier: a ticker, TICKER:MIC, ISIN, CUSIP, SEDOL, FIGI, CIK or LEI. The securities it matches show below as you type.");
            Ui.label(area, "Identifier type");
            type = new Combo(area, SWT.READ_ONLY);
            for (String t : Db.IDENTIFIER_TYPES) type.add(t.equals("Auto") ? "Detect" : t);
            type.select(0);
            QuickHelp.help(type, "Identifier Type", "Detect works out the kind of identifier; pick one when a value could be several (a ticker on several exchanges).");
            Ui.label(area, "Enter");
            Composite how = new Composite(area, SWT.NONE);
            how.setLayout(Ui.grid(2, 0, 12));
            shares = new Button(how, SWT.RADIO);
            shares.setText("Shares");
            shares.setSelection(true);
            weight = new Button(how, SWT.RADIO);
            weight.setText("Weight %");
            Ui.label(area, "Amount");
            amount = new Text(area, SWT.BORDER);
            amount.setMessage("100");
            amount.setLayoutData(Ui.fillH());
            QuickHelp.help(amount, "Shares or Weight", "The number of shares, or the target weight in percent (2.5 for 2.5%).");

            Group box = new Group(area, SWT.NONE);
            box.setText("Matches");
            box.setLayout(Ui.grid(1, 8, 4));
            GridData bg = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
            bg.heightHint = 150;
            box.setLayoutData(bg);
            matchesBox = box;
            problemsLabel(area, 2);
            failuresLabel = new Label(area, SWT.WRAP);
            failuresLabel.setForeground(Ui.RED);
            failuresLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
            Label note = Ui.wrap(area, "Add keeps the dialog open for the next holding; an existing holding of the same security is replaced.");
            note.setForeground(Ui.SECONDARY);
            note.setFont(Ui.small());
            ((GridData) note.getLayoutData()).horizontalSpan = 2;

            identifier.addListener(SWT.Modify, e -> lookUp());
            type.addListener(SWT.Selection, e -> lookUp());
            amount.addListener(SWT.Modify, e -> check());
            shares.addListener(SWT.Selection, e -> {
                amount.setMessage(weight.getSelection() ? "2.5" : "100");
                check();
            });
            weight.addListener(SWT.Selection, e -> {
                amount.setMessage(weight.getSelection() ? "2.5" : "100");
                check();
            });
            showMatches();
            return area;
        }

        private String typeValue() { return Db.IDENTIFIER_TYPES.get(Math.max(0, type.getSelectionIndex())); }

        /** Looks the identifier up 250 ms after the last key, as the Mac's task(id:) does. */
        private void lookUp() {
            int gen = ++generation;
            searched = false;
            String text = identifier.getText(), t = typeValue();
            showMatches();
            getShell().getDisplay().timerExec(250, () -> {
                if (gen != generation || getShell() == null || getShell().isDisposed()) return;
                Background.run(identifier, () -> hub.db.resolve(text, t), m -> {
                    if (gen != generation) return;
                    matches = m;
                    searched = !text.trim().isEmpty();
                    showMatches();
                });
            });
        }

        private void showMatches() {
            for (Control c : matchesBox.getChildren()) c.dispose();
            if (matches.isEmpty()) {
                Label l = Ui.label(matchesBox, identifier.getText().isEmpty() ? "Type an identifier." : searched ? "No match." : "Looking…");
                l.setForeground(Ui.SECONDARY);
            } else {
                for (int i = 0; i < Math.min(6, matches.size()); i++) {
                    ResolveMatch m = matches.get(i);
                    Composite row = new Composite(matchesBox, SWT.NONE);
                    row.setLayout(Ui.grid(4, 0, 6));
                    row.setLayoutData(Ui.fillH());
                    Label mark = Ui.label(row, i == 0 ? "●" : "○");
                    mark.setForeground(i == 0 ? Ui.ACCENT : Ui.SECONDARY);
                    Ui.label(row, m.ticker(), Ui.bold());
                    Label nm = Ui.label(row, m.name());
                    nm.setLayoutData(Ui.fillH());
                    Label info = Ui.label(row, m.matchedType() + " · " + (m.isin().isEmpty() ? m.type() : m.isin()));
                    info.setForeground(Ui.SECONDARY);
                }
            }
            matchesBox.layout(true, true);
            check();
        }

        private Double value() {
            String s = amount.getText().replace(",", "").replace("%", "").trim();
            if (s.isEmpty()) return null;
            try {
                return Double.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        List<String> problems() {
            List<String> out = new ArrayList<>();
            String id = identifier.getText();
            if (searched && matches.isEmpty() && !id.isEmpty()) out.add("No security found for " + id);
            if (matches.size() > 1 && !List.of("ISIN", "CUSIP", "FIGI", "COMPOSITE_FIGI", "SHARE_CLASS_FIGI").contains(matches.get(0).matchedType()))
                out.add(matches.size() + " securities match; pick an identifier type, or use an ISIN, CUSIP, FIGI or TICKER:MIC");
            Double v = value();
            if (!amount.getText().isEmpty() && v == null) out.add(weight.getSelection() ? "Weight must be a number (percent)" : "Shares must be a number");
            if (v != null && weight.getSelection() && !(v >= -100 && v <= 100)) out.add("Weight must be between -100% and 100%");
            return out;
        }

        @Override
        protected boolean okAllowed() { return !matches.isEmpty() && value() != null; }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.CANCEL_ID, "Close", false);
            createButton(parent, IDialogConstants.OK_ID, "Add", true);
            check();
        }

        @Override
        protected void okPressed() {
            Double v = value();
            if (matches.isEmpty() || v == null || !problems().isEmpty()) return;
            boolean byWeight = weight.getSelection();
            HoldingEntry e = new HoldingEntry(identifier.getText(), typeValue(), byWeight ? null : v, byWeight ? v : null);
            getButton(IDialogConstants.OK_ID).setEnabled(false);
            add.accept(List.of(e), f -> {
                if (getShell() == null || getShell().isDisposed()) return;
                failures = f;
                failuresLabel.setText(Ui.escape(String.join("\n", f)));
                if (f.isEmpty()) {
                    identifier.setText("");
                    amount.setText("");
                    matches = List.of();
                    searched = false;
                    showMatches();
                    identifier.setFocus();
                }
                check();
            });
        }

        @Override
        protected Point getInitialSize() {
            Point p = super.getInitialSize();
            return new Point(Math.max(p.x, 600), p.y);
        }
    }

    // MARK: Paste holdings

    static final class PasteHoldingsDialog extends Dialog {
        private final String heading;
        private final BiConsumer<List<HoldingEntry>, Consumer<List<String>>> add;
        private Text text;
        private Label ready, added;
        private Text failures;
        private boolean busy;

        PasteHoldingsDialog(Shell parent, String heading, BiConsumer<List<HoldingEntry>, Consumer<List<String>>> add) {
            super(parent);
            this.heading = heading;
            this.add = add;
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Paste Holdings");
        }

        record Parsed(List<HoldingEntry> entries, List<String> bad) {}

        /** Lines of "identifier [type] value": value is shares, or a weight when it ends with %. */
        static Parsed parse(String text) {
            List<HoldingEntry> entries = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            for (String raw : text.split("\\R")) {
                String[] parts = java.util.Arrays.stream(raw.split("[,\\t ;]+")).filter(s -> !s.isEmpty()).toArray(String[]::new);
                if (parts.length < 2) {
                    if (!raw.trim().isEmpty()) bad.add(raw);
                    continue;
                }
                String number = parts[parts.length - 1].replace(",", "");
                boolean isWeight = number.endsWith("%");
                number = number.replace("%", "");
                double value;
                try {
                    value = Double.parseDouble(number);
                } catch (NumberFormatException e) {
                    bad.add(raw);
                    continue;
                }
                String type = parts.length >= 3 && Db.IDENTIFIER_TYPES.contains(parts[1].toUpperCase()) ? parts[1].toUpperCase() : "Auto";
                entries.add(new HoldingEntry(parts[0], type, isWeight ? null : value, isWeight ? value : null));
            }
            return new Parsed(entries, bad);
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(Ui.grid(1, 14, 8));
            Ui.label(area, heading, Ui.subheading());
            Ui.wrap(area, "One holding per line: identifier, optional identifier type, then shares or a weight ending in %. Separated by commas, "
                    + "tabs or spaces, e.g. \"AAPL 100\", \"US5949181045, 4.5%\", \"2046251 SEDOL 50\".").setForeground(Ui.SECONDARY);
            text = new Text(area, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
            text.setFont(Ui.mono());
            GridData tg = Ui.fill();
            tg.heightHint = 220;
            text.setLayoutData(tg);
            QuickHelp.help(text, "Holdings", "Paste or type one holding per line: an identifier, optionally its type, then shares or a weight ending in %.");
            ready = Ui.label(area, "");
            ready.setLayoutData(Ui.fillH());
            added = Ui.label(area, "");
            added.setForeground(Ui.GREEN);
            added.setLayoutData(Ui.fillH());
            failures = new Text(area, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            failures.setForeground(Ui.RED);
            GridData fg = new GridData(SWT.FILL, SWT.TOP, true, false);
            fg.heightHint = 80;
            failures.setLayoutData(fg);
            Ui.setVisible(failures, false);
            text.addListener(SWT.Modify, e -> update());
            update();
            return area;
        }

        private void update() {
            Parsed p = parse(text.getText());
            ready.setText(p.entries().size() + " holdings ready" + (p.bad().isEmpty() ? "" : ", " + p.bad().size() + " lines not understood"));
            ready.setForeground(p.bad().isEmpty() ? Ui.SECONDARY : Ui.ORANGE);
            Button ok = getButton(IDialogConstants.OK_ID);
            if (ok != null) ok.setEnabled(!p.entries().isEmpty() && !busy);
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.CANCEL_ID, "Close", false);
            createButton(parent, IDialogConstants.OK_ID, "Add Holdings", true);
            update();
        }

        @Override
        protected void okPressed() {
            List<HoldingEntry> entries = parse(text.getText()).entries();
            if (entries.isEmpty()) return;
            busy = true;
            update();
            add.accept(entries, f -> {
                if (getShell() == null || getShell().isDisposed()) return;
                busy = false;
                int n = entries.size() - f.size();
                added.setText(n > 0 ? "Added " + n + "." : "");
                failures.setText(String.join("\n", f));
                Ui.setVisible(failures, !f.isEmpty());
                if (f.isEmpty()) text.setText("");
                update();
                getShell().layout(true, true);
            });
        }

        @Override
        protected Point getInitialSize() {
            Point p = super.getInitialSize();
            return new Point(Math.max(p.x, 600), p.y);
        }
    }

    // MARK: New snapshot

    static final class NewSnapshotDialog extends Dialog {
        private DateTime date;
        LocalDate chosen = LocalDate.now();

        NewSnapshotDialog(Shell parent) {
            super(parent);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("New Snapshot");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(Ui.grid(2, 14, 8));
            Ui.label(area, "Date");
            date = new DateTime(area, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
            date.setDate(chosen.getYear(), chosen.getMonthValue() - 1, chosen.getDayOfMonth());
            QuickHelp.help(date, "Date", "The new snapshot's date. Its holdings start as a copy of the latest earlier snapshot.");
            Label note = Ui.label(area, "Copies the latest earlier snapshot.");
            note.setForeground(Ui.SECONDARY);
            note.setLayoutData(Ui.span(2));
            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            createButton(parent, IDialogConstants.OK_ID, "Create", true);
        }

        @Override
        protected void okPressed() {
            chosen = LocalDate.of(date.getYear(), date.getMonth() + 1, date.getDay());
            super.okPressed();
        }
    }
}
