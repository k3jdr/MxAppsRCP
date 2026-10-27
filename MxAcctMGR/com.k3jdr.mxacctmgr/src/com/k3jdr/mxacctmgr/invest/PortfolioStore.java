package com.k3jdr.mxacctmgr.invest;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mxacctmgr.App;
import com.k3jdr.mxacctmgr.ErrorAlert;
import com.k3jdr.mxacctmgr.Settings;
import com.k3jdr.mxacctmgr.port.AccountsClient;
import com.k3jdr.mxacctmgr.port.ActiveSettings;
import com.k3jdr.mxacctmgr.port.AttributionEngine;
import com.k3jdr.mxacctmgr.port.AttributionResult;
import com.k3jdr.mxacctmgr.port.Engine;
import com.k3jdr.mxacctmgr.port.Fmt;
import com.k3jdr.mxacctmgr.port.Holding;
import com.k3jdr.mxacctmgr.port.LotOrder;
import com.k3jdr.mxacctmgr.port.OptimizationInputs;
import com.k3jdr.mxacctmgr.port.OptimizationResult;
import com.k3jdr.mxacctmgr.port.Portfolio;
import com.k3jdr.mxacctmgr.port.PortfolioStyle;
import com.k3jdr.mxacctmgr.port.SRMAccount;
import com.k3jdr.mxacctmgr.port.SRMBenchmarkInfo;
import com.k3jdr.mxacctmgr.port.SRMRegistry;
import com.k3jdr.mxacctmgr.port.SampleLesson;
import com.k3jdr.mxacctmgr.port.Samples;
import com.k3jdr.mxacctmgr.port.Security;
import com.k3jdr.mxacctmgr.port.ServiceClient;
import com.k3jdr.mxacctmgr.port.ServiceException;
import com.k3jdr.mxacctmgr.port.TrackingSettings;

/**
 * The accounts and My Portfolio (PortStore.swift and PortfolioFile.swift): the samples and the open portfolio, read
 * from the security master through the accounts service; the open portfolio's saved state for Save, Revert and the
 * unsaved-change check; the steps' preferences and results. Changed on the UI thread; the service calls run on
 * background threads.
 */
public final class PortfolioStore {
    public static final String MINE = "mine";

    private final App app;
    private final List<Portfolio> portfolios = new ArrayList<>();
    private String selectedID;

    /** The code of the open portfolio in the security master ("" when none is open). */
    public String srmCode = Settings.PREFS.get("srmAccount", "MY_PORTFOLIO");
    public int openVersion;
    public String mineLoadedCode;
    private Portfolio saved;
    private PortfolioStyle savedApproach = PortfolioStyle.ACTIVE;
    private JsonObject savedSettings = new JsonObject();

    public List<SRMBenchmarkInfo> srmBenchmarks = List.of();
    public List<String> srmAccountCodes = List.of();
    public String srmStatus;
    public boolean srmBusy;
    /** Why the accounts couldn't be read (accounts service or database down), shown instead of any data. */
    public String dataProblem;
    public boolean accountsLoaded;
    public boolean loading;

    public OptimizationInputs srmInputs;
    public String srmInputsNote;

    public ActiveSettings activeSettings = new ActiveSettings();
    public TrackingSettings trackingSettings = new TrackingSettings();
    public OptimizationResult activeResult;
    public OptimizationResult trackResult;
    public List<Engine.CurvePoint> trackCurve = List.of();
    public AttributionResult attribution;
    public AttributionEngine.Method attributionMethod = AttributionEngine.Method.BRINSON_FACHLER;
    public String masterPortfolio = Settings.PREFS.get("masterPortfolio", "DEMO_GROWTH");
    public boolean attributeMaster;
    public LotOrder lotOrder = LotOrder.HIFO;
    public double longTermRate = 0.15;
    public double shortTermRate = 0.32;
    public double bandWidth = 0.05;
    /** The holdings selected in My Holdings. */
    public Set<String> holdingSelection = new HashSet<>();

    public PortfolioStore(App app) {
        this.app = app;
        portfolios.addAll(Samples.placeholders());
        Portfolio mine = Samples.myPortfolio();
        if (srmCode.isEmpty()) {
            mine.srmCode = null;
            mine.name = "No portfolio open";
        }
        portfolios.add(mine);
        String s = Settings.PREFS.get("sample", "");
        selectedID = portfolios.stream().anyMatch(p -> p.id.equals(s)) ? s : "SAMPLE_CORE_SATELLITE";
    }

    // Accounts

    public List<Portfolio> portfolios() { return portfolios; }

    public String selectedID() { return selectedID; }

    public void select(String id) {
        if (id == null || id.equals(selectedID) || portfolios.stream().noneMatch(p -> p.id.equals(id))) return;
        selectedID = id;
        Settings.PREFS.put("sample", id);
        clearResults();
        srmInputs = null;
        app.changed();
    }

    /** The selected account (My Portfolio on the Investments steps). */
    public Portfolio portfolio() {
        return portfolios.stream().filter(p -> p.id.equals(selectedID)).findFirst().orElse(portfolios.get(0));
    }

    public Portfolio mine() {
        return portfolios.stream().filter(p -> p.id.equals(MINE)).findFirst().orElse(null);
    }

    /** Replaces an account (an edit of My Portfolio waits for Save). */
    public void update(Portfolio p) {
        for (int i = 0; i < portfolios.size(); i++) {
            if (portfolios.get(i).id.equals(p.id)) portfolios.set(i, p);
        }
        clearResults();
        srmInputs = null;
        app.changed();
    }

    /** The open portfolio (null when none is open). */
    public Portfolio openPortfolio() { return mineLoadedCode == null ? null : mine(); }

    public void clearResults() {
        activeResult = null;
        trackResult = null;
        trackCurve = List.of();
        attribution = null;
    }

    /** The optimizers' inputs: the analytics service's for the selected account (empty until loaded). */
    public OptimizationInputs inputs() { return srmInputs != null ? srmInputs : OptimizationInputs.empty(); }

    // Reading the accounts

    private record Loaded(List<Portfolio> samples, List<SRMBenchmarkInfo> benchmarks, String problem, Opened mine) {}

    private record Opened(Portfolio mine, int version, PortfolioStyle approach, JsonObject settings, String status,
                          List<SRMBenchmarkInfo> benchmarks, List<String> codes, String problem) {}

    /** Reads every account from the security master: the samples and My Portfolio, with their benchmarks. */
    public void loadAccounts() {
        if (loading) return;
        loading = true;
        app.changed();
        String code = srmCode;
        Background.run(app.shell, () -> {
            List<Portfolio> samples = new ArrayList<>();
            List<SRMBenchmarkInfo> benchmarks = List.of();
            String problem = null;
            try {
                benchmarks = AccountsClient.benchmarks();
                for (SampleLesson l : Samples.LESSONS) {
                    SRMAccount a = AccountsClient.account(l.code());
                    Portfolio p = Samples.portfolio(a, l);
                    p.apply(a);
                    samples.add(p);
                    useBenchmark(p.benchmark, benchmarks);
                }
            } catch (ServiceException e) {
                problem = "The sample accounts are in the security master: " + e.getMessage()
                        + " Start the services and make sure the database is reachable.";
            }
            Opened mine = problem == null ? open(code) : null;
            return new Loaded(samples, benchmarks, problem, mine);
        }, loaded -> {
            loading = false;
            dataProblem = loaded.problem();
            if (loaded.problem() == null) {
                srmBenchmarks = loaded.benchmarks();
                for (Portfolio p : loaded.samples()) replace(p);
                accountsLoaded = true;
                if (loaded.mine() != null) applyOpened(code, loaded.mine());
            }
            clearResults();
            srmInputs = null;
            app.changed();
        }, failed -> {
            loading = false;
            dataProblem = failed.getMessage();
            app.changed();
        });
    }

    private void replace(Portfolio p) {
        for (int i = 0; i < portfolios.size(); i++) {
            if (portfolios.get(i).id.equals(p.id)) portfolios.set(i, p);
        }
    }

    /** Registers a security-master benchmark's members so sector and look-through views can use it (off the UI thread). */
    static void useBenchmark(String code, List<SRMBenchmarkInfo> known) {
        if (code == null || SRMRegistry.shared().benchmark(code) != null) return;
        if (known.stream().noneMatch(b -> b.code().equals(code))) return;
        try {
            SRMRegistry.shared().register(AccountsClient.benchmarkWeights(code));
        } catch (ServiceException e) {
            // the benchmark's members are missing from the comparisons; the rest works
        }
    }

    /** Reads (or starts) the account {@code code} as My Portfolio, off the UI thread. */
    private Opened open(String code) {
        if (code == null || code.isEmpty()) return null;
        try {
            List<SRMBenchmarkInfo> benchmarks = AccountsClient.benchmarks();
            List<String> codes;
            try {
                codes = AccountsClient.accounts();
            } catch (ServiceException e) {
                codes = List.of();
            }
            Portfolio mine = Samples.myPortfolio();
            mine.srmCode = code;
            mine.benchmark = benchmarks.stream().filter(b -> b.code().equals("SP500")).map(SRMBenchmarkInfo::code).findFirst()
                    .orElse(benchmarks.isEmpty() ? "SP500" : benchmarks.get(0).code());
            int version = 0;
            PortfolioStyle approach = null;
            JsonObject settings = null;
            String status;
            if (codes.stream().anyMatch(c -> c.equalsIgnoreCase(code))) {
                SRMAccount account = AccountsClient.account(code);
                approach = mine.apply(account);
                version = account.version() == null ? 1 : account.version();
                settings = account.settings();
                status = "Opened " + account.code() + " (version " + version + "): " + account.lots().size() + " lots, "
                        + Fmt.money(account.totalValue()) + ".";
            } else {
                status = "New portfolio " + code + ": add securities, then File ▸ Save.";
            }
            useBenchmark(mine.benchmark, benchmarks);
            return new Opened(mine, version, approach, settings, status, benchmarks, codes, null);
        } catch (ServiceException e) {
            return new Opened(null, 0, null, null, null, List.of(), List.of(),
                    "My Portfolio is in the security master: " + e.getMessage());
        }
    }

    private void applyOpened(String code, Opened o) {
        if (o.problem() != null) {
            if (dataProblem == null) dataProblem = o.problem();
            return;
        }
        srmBenchmarks = o.benchmarks();
        srmAccountCodes = o.codes();
        if (o.settings() != null && o.settings().size() > 0) applySettings(o.settings());
        if (o.approach() != null) app.setApproach(o.approach());
        srmStatus = o.status();
        srmCode = code;
        Settings.PREFS.put("srmAccount", code);
        mineLoadedCode = code;
        replace(o.mine());
        markSaved(o.mine(), o.version());
        Settings.PREFS.pushRecent("recentPortfolios", code, 8);
        clearResults();
        srmInputs = null;
    }

    /** Opens a portfolio from the security master (File ▸ Open Portfolio…, Open Recent, mxacctmgr://my-portfolio/CODE). */
    public void requestOpen(String code, Runnable then) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty()) return;
        srmBusy = true;
        app.changed();
        Background.run(app.shell, () -> open(c), o -> {
            srmBusy = false;
            if (o != null) applyOpened(c, o);
            if (o != null && o.problem() != null) error(o.problem());
            select(MINE);
            app.changed();
            if (then != null) then.run();
        });
    }

    /** Closes the open portfolio: the Investments steps then ask to open or create one. */
    public void closePortfolio() {
        Portfolio empty = Samples.myPortfolio();
        empty.srmCode = null;
        empty.name = "No portfolio open";
        replace(empty);
        mineLoadedCode = null;
        openVersion = 0;
        srmCode = "";
        Settings.PREFS.put("srmAccount", "");
        srmStatus = null;
        srmInputs = null;
        clearResults();
        app.changed();
    }

    public List<String> recentPortfolios() { return Settings.PREFS.getList("recentPortfolios"); }

    public void clearRecent() { Settings.PREFS.remove("recentPortfolios"); }

    // Saved state and unsaved changes

    /** The preferences as the screens have them now (srm.portfolio.settings). */
    public JsonObject currentSettings() {
        return AccountsClient.settingsJson(activeSettings, trackingSettings, lotOrder, longTermRate, shortTermRate, bandWidth,
                attributionMethod);
    }

    /** Takes a portfolio's saved preferences (the ones it has; the rest stay as they are). */
    public void applySettings(JsonObject s) {
        if (s.has("active") && s.get("active").isJsonObject()) activeSettings = ActiveSettings.fromJson(s.getAsJsonObject("active"));
        if (s.has("tracking") && s.get("tracking").isJsonObject()) trackingSettings = TrackingSettings.fromJson(s.getAsJsonObject("tracking"));
        if (s.has("lotOrder") && s.get("lotOrder").isJsonPrimitive()) {
            LotOrder o = LotOrder.fromRawValue(s.get("lotOrder").getAsString());
            if (o != null) lotOrder = o;
        }
        if (s.has("longTermRate") && s.get("longTermRate").isJsonPrimitive()) longTermRate = s.get("longTermRate").getAsDouble();
        if (s.has("shortTermRate") && s.get("shortTermRate").isJsonPrimitive()) shortTermRate = s.get("shortTermRate").getAsDouble();
        if (s.has("bandWidth") && s.get("bandWidth").isJsonPrimitive()) bandWidth = s.get("bandWidth").getAsDouble();
        if (s.has("attributionMethod") && s.get("attributionMethod").isJsonPrimitive()) {
            AttributionEngine.Method m = AttributionEngine.Method.fromRawValue(s.get("attributionMethod").getAsString());
            if (m != null) attributionMethod = m;
        }
    }

    /** Records {@code mine} as the saved state of the open portfolio (after Open, Save or Revert). */
    public void markSaved(Portfolio mine, int version) {
        openVersion = version;
        saved = mine.copy();
        savedApproach = app.approach();
        savedSettings = currentSettings();
    }

    /** What differs from the saved portfolio, in words (empty: nothing to save). */
    public List<String> looseEnds() {
        Portfolio p = openPortfolio();
        List<String> out = new ArrayList<>();
        if (p == null || saved == null) return out;
        if (!p.holdings.equals(saved.holdings)) {
            Set<String> before = new HashSet<>(), now = new HashSet<>();
            saved.holdings.forEach(h -> before.add(h.ticker));
            p.holdings.forEach(h -> now.add(h.ticker));
            long added = now.stream().filter(t -> !before.contains(t)).count();
            long removed = before.stream().filter(t -> !now.contains(t)).count();
            long changed = p.holdings.stream().filter(h -> saved.holdings.stream()
                    .anyMatch(s -> s.ticker.equals(h.ticker) && !s.equals(h))).count();
            List<String> parts = new ArrayList<>();
            if (added > 0) parts.add(added + " added");
            if (removed > 0) parts.add(removed + " removed");
            if (changed > 0) parts.add(changed + " changed");
            out.add("Holdings: " + String.join(", ", parts));
        }
        if (!p.name.equals(saved.name)) out.add("Name: " + saved.name + " → " + p.name);
        if (!p.benchmark.equals(saved.benchmark)) out.add("Benchmark: " + saved.benchmark + " → " + p.benchmark);
        if (p.account != saved.account) out.add("Account type: " + saved.account.title + " → " + p.account.title);
        if (app.approach() != savedApproach) out.add("Approach: " + savedApproach.title + " → " + app.approach().title);
        if (!currentSettings().equals(savedSettings)) out.add("Preferences (optimizer, tracker, taxes, lots, bands or attribution)");
        return out;
    }

    public boolean isDirty() { return !looseEnds().isEmpty(); }

    /** Saves the open portfolio as its next version (File ▸ Save); {@code done} gets whether it was saved. */
    public void save(Consumer<Boolean> done) {
        Portfolio mine = mine();
        if (mine == null || !mine.isSRM() || mine.srmCode == null || mineLoadedCode == null
                || !mineLoadedCode.equalsIgnoreCase(mine.srmCode)) {
            if (done != null) done.accept(false);
            return;
        }
        String code = mine.srmCode;
        Portfolio snapshot = mine.copy();
        JsonObject body = AccountsClient.saveBody(snapshot, app.approach(), openVersion, currentSettings(), String.join("; ", looseEnds()));
        srmBusy = true;
        app.changed();
        Background.run(app.shell, () -> AccountsClient.save(code, body), savedAccount -> {
            srmBusy = false;
            String time = new SimpleDateFormat("h:mm a").format(new Date());
            srmStatus = "Saved " + savedAccount.code() + " version " + (savedAccount.version() == null ? 0 : savedAccount.version())
                    + " at " + time + ": " + savedAccount.lots().size() + " lots, " + Fmt.money(savedAccount.totalValue())
                    + (savedAccount.unpriced().isEmpty() ? "." : ". No prices yet for " + String.join(", ", savedAccount.unpriced()) + ".");
            if (!srmAccountCodes.contains(savedAccount.code())) {
                List<String> codes = new ArrayList<>(srmAccountCodes);
                codes.add(savedAccount.code());
                srmAccountCodes = codes;
            }
            markSaved(snapshot, savedAccount.version() == null ? openVersion + 1 : savedAccount.version());
            srmInputs = null;
            app.changed();
            if (done != null) done.accept(true);
        }, failed -> {
            srmBusy = false;
            if (failed instanceof ServiceException se && se.isConflict()) {
                srmStatus = "Not saved: changed in the security master since it was opened.";
                app.changed();
                ConflictDialog.show(app, code, se.getMessage());
            } else {
                srmStatus = "Not saved: " + failed.getMessage();
                app.changed();
                error(code + " was not saved: " + failed.getMessage());
            }
            if (done != null) done.accept(false);
        });
    }

    /** Reads the open portfolio again from the security master, dropping unsaved edits (File ▸ Revert to Saved). */
    public void revert() {
        if (srmCode.isEmpty()) return;
        requestOpen(srmCode, null);
    }

    /** Copies a sample's holdings and lots into the open portfolio to edit (saved with File ▸ Save). */
    public void copyToMine(Portfolio p) {
        Portfolio mine = mine();
        if (openPortfolio() == null || mine == null) {
            error("No portfolio is open: File ▸ New Portfolio… (it can start from a sample) or Open Portfolio…");
            return;
        }
        Portfolio m = mine.copy();
        m.holdings = new ArrayList<>();
        for (Holding h : p.holdings) m.holdings.add(h.copy());
        m.benchmark = p.benchmark;
        m.account = p.account;
        update(m);
        select(MINE);
    }

    public void removeFromMine(String ticker) {
        Portfolio mine = mine();
        if (mine == null) return;
        Portfolio m = mine.copy();
        m.holdings.removeIf(h -> h.ticker.equals(ticker));
        update(m);
    }

    // Risk inputs and runs

    /** The analytics service's inputs for the selected account (off the UI thread). */
    private ServiceClient.InputsAnswer fetchInputs(Portfolio p, boolean dirty, boolean active) throws ServiceException {
        if (p.srmCode == null) throw ServiceException.rejected("The account isn't in the security master.");
        String mode = active ? "active" : "tracking";
        if (MINE.equals(p.id) && dirty) {
            // Unsaved edits: the service reads saved holdings, so it gets the securities and the benchmark, and the
            // weights come from the holdings on screen.
            JsonArray tickers = new JsonArray();
            for (Holding h : p.holdings) {
                Security s = h.security();
                if (!"CASH".equals(h.ticker) && s != null) tickers.add(s.displayTicker());
            }
            JsonObject body = new JsonObject();
            body.add("securities", tickers);
            body.addProperty("benchmark", p.benchmark);
            body.addProperty("mode", mode);
            if (!active) body.addProperty("alpha", "none");
            ServiceClient.InputsAnswer a = ServiceClient.riskInputs(body);
            double invested = p.holdings.stream().filter(h -> !"CASH".equals(h.ticker)).mapToDouble(h -> h.value).sum();
            double[] current = new double[a.inputs().n()];
            for (int i = 0; i < current.length; i++) {
                String key = a.inputs().assets.get(i);
                double v = p.holdings.stream().filter(h -> h.ticker.equals(key)).mapToDouble(h -> h.value).sum();
                current[i] = invested > 0 ? v / invested : 0;
            }
            return new ServiceClient.InputsAnswer(a.inputs().withCurrent(current), a.note() + " (unsaved holdings)");
        }
        return ServiceClient.srmInputs(p.srmCode, p.benchmark, mode);
    }

    /** Loads the risk inputs (Risk Today and the optimizers need them); {@code then} runs when they are in. */
    public void loadInputs(Runnable then) {
        Portfolio p = portfolio().copy();
        boolean dirty = MINE.equals(p.id) && isDirty();
        boolean active = MINE.equals(p.id) ? app.active() : p.style != PortfolioStyle.PASSIVE;
        run(() -> fetchInputs(p, dirty, active), a -> {
            srmInputs = a.inputs();
            srmInputsNote = a.note();
            if (then != null) then.run();
        }, e -> srmInputsNote = e);
    }

    /** Optimize My Portfolio (active, step 4). */
    public void optimize() {
        Portfolio p = portfolio().copy();
        boolean dirty = MINE.equals(p.id) && isDirty();
        OptimizationInputs have = srmInputs;
        ActiveSettings s = activeSettings.copy();
        App.Engine engine = app.engine();
        record Out(ServiceClient.InputsAnswer inputs, OptimizationResult result) {}
        run(() -> {
            ServiceClient.InputsAnswer in = have != null ? new ServiceClient.InputsAnswer(have, srmInputsNote) : fetchInputs(p, dirty, true);
            if (in.inputs().n() == 0) return new Out(in, null);
            OptimizationResult r = engine == App.Engine.SERVICES ? ServiceClient.active(in.inputs(), s) : Engine.active(in.inputs(), s);
            return new Out(in, r);
        }, out -> {
            srmInputs = out.inputs().inputs();
            srmInputsNote = out.inputs().note();
            activeResult = out.result();
            if (out.result() != null) {
                app.setMessage("Optimized with " + out.result().engine() + " in " + Math.round(out.result().milliseconds()) + " ms");
            }
        }, null);
    }

    /** Build My Index Tracker (passive, step 4), with the tracking-error-vs-names curve. */
    public void buildTracker() {
        Portfolio p = portfolio().copy();
        boolean dirty = MINE.equals(p.id) && isDirty();
        OptimizationInputs have = srmInputs;
        TrackingSettings s = trackingSettings.copy();
        App.Engine engine = app.engine();
        record Out(ServiceClient.InputsAnswer inputs, OptimizationResult result, List<Engine.CurvePoint> curve) {}
        run(() -> {
            ServiceClient.InputsAnswer in = have != null ? new ServiceClient.InputsAnswer(have, srmInputsNote) : fetchInputs(p, dirty, false);
            if (in.inputs().n() == 0) return new Out(in, null, List.of());
            OptimizationResult r = engine == App.Engine.SERVICES ? ServiceClient.track(in.inputs(), s) : Engine.track(in.inputs(), s);
            return new Out(in, r, Engine.trackingCurve(in.inputs(), s.selection));
        }, out -> {
            srmInputs = out.inputs().inputs();
            srmInputsNote = out.inputs().note();
            trackResult = out.result();
            trackCurve = out.curve();
            if (out.result() != null) {
                app.setMessage("Tracker built with " + out.result().engine() + " in " + Math.round(out.result().milliseconds()) + " ms");
            }
        }, null);
    }

    /** Run Attribution (step 6): the analytics service attributes the account from its holdings history. */
    public void attribute() {
        Portfolio p = portfolio();
        String code = attributeMaster ? masterPortfolio : (p.srmCode == null ? "" : p.srmCode);
        AttributionEngine.Method method = attributionMethod;
        run(() -> ServiceClient.attribution(code, method), r -> {
            attribution = r;
            app.setMessage("Attribution: " + r.engine());
        }, null);
    }

    /** Runs {@code work} off the UI thread with the busy mark, then {@code done}; a failure shows (or goes to {@code failed}). */
    public <T> void run(Callable<T> work, Consumer<T> done, Consumer<String> failed) {
        app.busy(true);
        Background.run(app.shell, work, r -> {
            app.busy(false);
            done.accept(r);
            app.changed();
        }, e -> {
            app.busy(false);
            if (e instanceof ServiceException se && se.isCancelled()) return;
            if (failed != null) failed.accept(e.getMessage());
            error(e.getMessage() == null ? e.toString() : e.getMessage());
            app.changed();
        });
    }

    /** The app's alert (ContentView's "MxAcctMGR" alert), with Layer 3 Services… when a service is involved. */
    public void error(String message) {
        ErrorAlert.show(app, message);
    }
}
