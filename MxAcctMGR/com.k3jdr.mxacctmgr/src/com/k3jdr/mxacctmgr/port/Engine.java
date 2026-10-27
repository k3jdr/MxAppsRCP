package com.k3jdr.mxacctmgr.port;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * The built-in engines, written to the same contracts as the Layer 3 services so their answers can be compared:
 * active (portfolio-java /optimize/active), track (portfolio-cpp /optimize/tracking) and risk (portfolio-cpp /risk).
 */
public final class Engine {
    private Engine() {}

    /** The engine name shown in results. */
    public static final String NAME = "Built-in (Java)";

    /** FISTA's answer: the weights, the iterations used, and whether it converged. */
    public record Solution(double[] w, int iterations, boolean converged) {}

    /** A point of the tracking-error curve. */
    public record CurvePoint(int names, double te) {}

    // Projection and solver

    /**
     * wᵢ = clip(vᵢ − τ, loᵢ, hiᵢ) with τ chosen so the weights add up to 1, exactly, from the sorted breakpoints of the
     * piecewise-linear sum.
     */
    public static double[] project(double[] v, double[] lo, double[] hi) {
        int n = v.length;
        if (n == 0) return new double[0];
        double[] points = new double[2 * n];
        for (int i = 0; i < n; i++) {
            points[2 * i] = v[i] - hi[i];
            points[2 * i + 1] = v[i] - lo[i];
        }
        Arrays.sort(points);
        // sum(τ) falls as τ rises; find neighbouring breakpoints around 1 and interpolate (linear between them).
        int a = 0, b = points.length - 1;
        if (!(sum(v, lo, hi, points[a]) >= 1 && sum(v, lo, hi, points[b]) <= 1)) {
            double tau = sum(v, lo, hi, points[a]) < 1 ? points[a] : points[b];
            return clip(v, lo, hi, tau);
        }
        while (b - a > 1) {
            int m = (a + b) / 2;
            if (sum(v, lo, hi, points[m]) >= 1) a = m; else b = m;
        }
        double sa = sum(v, lo, hi, points[a]), sb = sum(v, lo, hi, points[b]);
        double tau = sa == sb ? points[a] : points[a] + (sa - 1) / (sa - sb) * (points[b] - points[a]);
        return clip(v, lo, hi, tau);
    }

    private static double sum(double[] v, double[] lo, double[] hi, double tau) {
        double s = 0;
        for (int i = 0; i < v.length; i++) s += Math.min(Math.max(v[i] - tau, lo[i]), hi[i]);
        return s;
    }

    private static double[] clip(double[] v, double[] lo, double[] hi, double tau) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = Math.min(Math.max(v[i] - tau, lo[i]), hi[i]);
        return out;
    }

    /** FISTA ascent of a concave objective given its gradient, step 1/L, over the projected set. */
    public static Solution fista(double[] start, double[] lo, double[] hi, double lipschitz, int maxIterations,
                                 UnaryOperator<double[]> gradient) {
        double[] x = project(start, lo, hi), y = x.clone();
        double t = 1.0;
        double step = 1 / Math.max(lipschitz, 1e-12);
        int n = x.length;
        for (int k = 1; k <= maxIterations; k++) {
            double[] g = gradient.apply(y);
            double[] moved = new double[n];
            for (int i = 0; i < n; i++) moved[i] = y[i] + step * g[i];
            double[] next = project(moved, lo, hi);
            double tNext = (1 + Math.sqrt(1 + 4 * t * t)) / 2;
            double delta = 0;
            for (int i = 0; i < n; i++) delta = Math.max(delta, Math.abs(next[i] - x[i]));
            double[] ny = new double[n];
            for (int i = 0; i < n; i++) ny[i] = next[i] + (t - 1) / tNext * (next[i] - x[i]);
            y = ny;
            x = next;
            t = tNext;
            if (delta < 1e-8) return new Solution(x, k, true);
        }
        return new Solution(x, maxIterations, false);
    }

    public static Solution fista(double[] start, double[] lo, double[] hi, double lipschitz, UnaryOperator<double[]> gradient) {
        return fista(start, lo, hi, lipschitz, 20_000, gradient);
    }

    // Active

    /**
     * Maximises αᵀw − λ/2·(w−b)ᵀΣ(w−b) − ρ/2·Σₛ max(0, |aₛ|−m)², long-only, fully invested, wᵢ ≤ maxWeight,
     * |wᵢ−bᵢ| ≤ maxActiveWeight; λ by bisection on log λ to meet a tracking-error target.
     */
    public static OptimizationResult active(OptimizationInputs inputs, ActiveSettings s) {
        long started = System.nanoTime();
        int n = inputs.n();
        double[] b = inputs.benchmark;
        double[] lo = new double[n], hi = new double[n];
        double loSum = 0, hiSum = 0;
        for (int i = 0; i < n; i++) {
            lo[i] = Math.max(0, b[i] - s.maxActiveWeight);
            hi[i] = Math.min(s.maxWeight, b[i] + s.maxActiveWeight);
            if (hi[i] < lo[i]) hi[i] = lo[i];
            loSum += lo[i];
            hiSum += hi[i];
        }
        if (loSum > 1 + 1e-12 || hiSum < 1 - 1e-12) {
            return failed("infeasible", new Decision("rerun_relaxed",
                    "The weight limits can't add up to 100%: raise the largest weight or the active weight allowed."), started);
        }
        List<String> sectorNames = new ArrayList<>(new TreeSet<>(inputs.sectors));
        int[] sectorOf = new int[n];
        for (int i = 0; i < n; i++) sectorOf[i] = sectorNames.indexOf(inputs.sectors.get(i));
        int largest = 0;
        for (int k = 0; k < sectorNames.size(); k++) {
            int c = 0;
            for (int i = 0; i < n; i++) if (sectorOf[i] == k) c++;
            largest = Math.max(largest, c);
        }
        if (sectorNames.isEmpty()) largest = 1;
        final int largestSector = largest;
        final double lmax = inputs.maxEigenvalue();
        final int sectorCount = sectorNames.size();

        // solve(λ, warm)
        java.util.function.BiFunction<Double, double[], Solution> solve = (lambda, warm) -> {
            double rho = s.sectorBand == null ? 0 : 20 * lambda * lmax;
            double lip = lambda * lmax + rho * largestSector;
            return fista(warm != null ? warm : b, lo, hi, lip, w -> {
                double[] act = new double[n];
                for (int i = 0; i < n; i++) act[i] = w[i] - b[i];
                double[] sa = inputs.mul(act);
                double[] g = new double[n];
                for (int i = 0; i < n; i++) g[i] = inputs.alpha[i] - lambda * sa[i];
                if (s.sectorBand != null) {
                    double band = s.sectorBand;
                    double[] sums = new double[sectorCount];
                    for (int i = 0; i < n; i++) sums[sectorOf[i]] += act[i];
                    for (int i = 0; i < n; i++) {
                        double a = sums[sectorOf[i]];
                        double excess = Math.max(0, Math.abs(a) - band);
                        g[i] -= rho * excess * (a >= 0 ? 1 : -1);
                    }
                }
                return g;
            });
        };

        double lambda = s.riskAversion;
        Solution result;
        if (s.trackingErrorTarget != null) {
            double target = s.trackingErrorTarget;
            double a = -1.0, hiL = 3.0;
            Solution best = null;
            double bestLambda = 0;
            double[] warm = null;
            for (int k = 0; k < 14; k++) {
                double mid = (a + hiL) / 2;
                Solution t = solve.apply(Math.pow(10, mid), warm);
                warm = t.w();
                double te = Math.sqrt(inputs.quad(minus(t.w(), b)));
                if (te > target) {
                    a = mid;
                } else {
                    hiL = mid;
                    best = t;
                    bestLambda = Math.pow(10, mid);
                }
            }
            if (best != null) {
                result = best;
                lambda = bestLambda;
            } else {
                lambda = Math.pow(10, hiL);
                result = solve.apply(lambda, warm);
            }
        } else {
            result = solve.apply(lambda, null);
        }

        double[] w = result.w();
        double[] act = minus(w, b);
        double te = Math.sqrt(Math.max(0, inputs.quad(act)));
        double alpha = 0;
        for (int i = 0; i < Math.min(inputs.alpha.length, n); i++) alpha += inputs.alpha[i] * act[i];
        double ir = te > 1e-9 ? alpha / te : 0;
        double turnover = 0;
        for (int i = 0; i < n; i++) turnover += Math.abs(w[i] - inputs.current[i]) / 2;
        Map<String, Double> sectorActive = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) sectorActive.merge(inputs.sectors.get(i), act[i], Double::sum);
        double worst = 0;
        for (double x : sectorActive.values()) worst = Math.max(worst, Math.abs(x));
        List<String> binding = new ArrayList<>();
        boolean atMax = false, atActive = false;
        for (int i = 0; i < n; i++) {
            if (w[i] >= s.maxWeight - 1e-6) atMax = true;
            if (Math.abs(act[i]) >= s.maxActiveWeight - 1e-6) atActive = true;
        }
        if (atMax && s.maxWeight < 1) binding.add("max_weight");
        if (atActive) binding.add("max_active_weight");
        if (s.sectorBand != null && worst >= s.sectorBand - 5e-4) binding.add("max_sector_active");
        if (s.trackingErrorTarget != null && te >= s.trackingErrorTarget - 1e-4) binding.add("tracking_error_target");
        if (s.maxTurnover != null && turnover > s.maxTurnover) binding.add("max_turnover");

        Decision decision;
        if (!result.converged()) {
            decision = new Decision("escalate", "The solver did not converge; review before acting.");
        } else if (s.sectorBand != null && worst > s.sectorBand + 0.002) {
            decision = new Decision("rerun_relaxed", "A sector is " + Fmt.pct(worst, 2) + " active, beyond the "
                    + Fmt.pct(s.sectorBand, 2) + " band: widen the band or the active-weight limit.");
        } else if (s.trackingErrorTarget != null && te > s.trackingErrorTarget * 1.05) {
            decision = new Decision("rerun_relaxed", "Tracking error " + Fmt.pct(te, 2) + " is above the "
                    + Fmt.pct(s.trackingErrorTarget, 2) + " target even at the highest risk aversion.");
        } else if (turnover < 0.02) {
            decision = new Decision("hold", "Turnover of " + Fmt.pct(turnover) + " is too small to be worth the trading cost.");
        } else if (s.maxTurnover != null && turnover > s.maxTurnover) {
            decision = new Decision("rebalance_partially", "Reaching the target means trading " + Fmt.pct(turnover)
                    + " of the account, more than the " + Fmt.pct(s.maxTurnover, 0) + " turnover limit: move part of the way now.");
        } else {
            decision = new Decision("rebalance", "Information ratio " + Fmt.fixed(ir, 2) + " at " + Fmt.pct(te, 2)
                    + " tracking error with " + Fmt.pct(turnover) + " turnover.");
        }
        double activeShare = 0;
        for (double x : act) activeShare += Math.abs(x) / 2;
        return new OptimizationResult(NAME, result.converged() ? "optimal" : "max_iterations",
                weightMap(inputs.assets, w), te, Math.sqrt(inputs.quad(minus(inputs.current, b))),
                alpha, ir, activeShare, turnover, countHeld(w), binding, sectorActive,
                trades(inputs, w), decision, lambda, "active_mean_variance", null, millis(started));
    }

    // Index tracking

    /** Which names a ≤ N-name tracker holds: the largest weights, or names spread across sectors in proportion to them. */
    public static List<Integer> selectNames(OptimizationInputs inputs, int maxNames, TrackingSettings.Selection method) {
        double[] bw = inputs.benchmark;
        List<Integer> members = new ArrayList<>();
        for (int i = 0; i < bw.length; i++) if (bw[i] > 0) members.add(i);
        if (!(maxNames > 0 && maxNames < members.size())) return members;
        List<Integer> byWeight = new ArrayList<>(members);
        byWeight.sort((x, y) -> Double.compare(bw[y], bw[x]));
        if (method == TrackingSettings.Selection.LARGEST) return new ArrayList<>(byWeight.subList(0, maxNames));
        // As portfolio-cpp: one name per sector, heaviest sectors first; then, one at a time, a name for the sector
        // furthest below its proportional share that still has names to add.
        Map<String, List<Integer>> groups = new HashMap<>();
        for (int i : byWeight) groups.computeIfAbsent(inputs.sectors.get(i), k -> new ArrayList<>()).add(i);
        Map<String, Double> weight = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
            double s = 0;
            for (int i : e.getValue()) s += bw[i];
            weight.put(e.getKey(), s);
        }
        List<String> bySize = new ArrayList<>(groups.keySet());
        bySize.sort((x, y) -> {
            double wx = weight.get(x), wy = weight.get(y);
            if (wx != wy) return wx > wy ? -1 : 1;
            return y.compareTo(x);
        });
        Map<String, Integer> quota = new HashMap<>();
        for (String g : bySize.subList(0, Math.min(maxNames, bySize.size()))) quota.put(g, 1);
        int used = quota.size();
        while (used < maxNames) {
            String pick = null;
            double gap = Double.NEGATIVE_INFINITY;
            for (String g : bySize) {
                int q = quota.getOrDefault(g, 0);
                if (q >= groups.get(g).size()) continue;
                double x = weight.get(g) * maxNames - q;
                if (x > gap) {
                    gap = x;
                    pick = g;
                }
            }
            if (pick == null) break;
            quota.merge(pick, 1, Integer::sum);
            used++;
        }
        List<Integer> out = new ArrayList<>();
        for (String g : new TreeSet<>(groups.keySet())) {
            List<Integer> names = groups.get(g);
            out.addAll(names.subList(0, Math.min(quota.getOrDefault(g, 0), names.size())));
        }
        return out;
    }

    /** The ≤ N-name portfolio with the smallest tracking error (full replication, largest weights, or stratified). */
    public static OptimizationResult track(OptimizationInputs inputs, TrackingSettings s) {
        long started = System.nanoTime();
        int n = inputs.n();
        double[] b = inputs.benchmark;
        int members = 0;
        for (double x : b) if (x > 0) members++;
        List<Integer> chosen = selectNames(inputs, s.maxNames, s.selection);
        String method = chosen.size() >= members ? "full_replication" : "sampled_" + s.selection.rawValue;
        if (chosen.size() * s.maxWeight < 1) {
            return failed("infeasible", new Decision("rerun_relaxed",
                    "Too few names for the weight cap: raise the number of names or the largest weight."), started);
        }
        // Names outside the selection are held at zero.
        double[] lo = new double[n], hi = new double[n];
        for (int i : chosen) hi[i] = s.maxWeight;
        double[] w;
        boolean converged;
        if (method.equals("full_replication")) {
            w = b.clone();
            converged = true;
        } else {
            Solution r = fista(b, lo, hi, inputs.maxEigenvalue(), x -> {
                double[] g = inputs.mul(minus(x, b));
                for (int i = 0; i < g.length; i++) g[i] = -g[i];
                return g;
            });
            w = r.w();
            converged = r.converged();
        }
        double[] act = minus(w, b);
        double te = Math.sqrt(Math.max(0, inputs.quad(act)));
        double currentTE = Math.sqrt(inputs.quad(minus(inputs.current, b)));
        double turnover = 0;
        for (int i = 0; i < n; i++) turnover += Math.abs(w[i] - inputs.current[i]) / 2;
        Map<String, Double> sectorActive = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) sectorActive.merge(inputs.sectors.get(i), act[i], Double::sum);
        double covered = 0;
        for (int i : chosen) covered += b[i];
        Decision decision;
        if (!converged) {
            decision = new Decision("escalate", "The solver did not converge; review before acting.");
        } else if (te > s.trackingErrorBudget) {
            decision = new Decision("rerun_relaxed", chosen.size() + " names track with " + Fmt.pct(te, 2)
                    + " error, above the " + Fmt.pct(s.trackingErrorBudget, 2) + " budget: hold more names.");
        } else if (currentTE <= s.trackingErrorBudget && turnover < 0.02) {
            decision = new Decision("hold", "The account already tracks within budget (" + Fmt.pct(currentTE, 2) + ").");
        } else if (s.maxTurnover != null && turnover > s.maxTurnover) {
            decision = new Decision("rebalance_partially", "The tracker is " + Fmt.pct(turnover)
                    + " away from today's holdings, more than the " + Fmt.pct(s.maxTurnover, 0) + " turnover limit.");
        } else {
            decision = new Decision("rebalance", "Today's tracking error " + Fmt.pct(currentTE, 2) + " → " + Fmt.pct(te, 2)
                    + " with " + chosen.size() + " names and " + Fmt.pct(turnover) + " turnover.");
        }
        double activeShare = 0;
        for (double x : act) activeShare += Math.abs(x) / 2;
        return new OptimizationResult(NAME, converged ? "optimal" : "max_iterations", weightMap(inputs.assets, w), te,
                currentTE, null, null, activeShare, turnover, countHeld(w), List.of(), sectorActive, trades(inputs, w),
                decision, null, method, covered, millis(started));
    }

    /** Tracking error for each number of names, to show the diminishing return of holding more. */
    public static List<CurvePoint> trackingCurve(OptimizationInputs inputs, TrackingSettings.Selection selection) {
        int members = 0;
        for (double x : inputs.benchmark) if (x > 0) members++;
        List<CurvePoint> out = new ArrayList<>();
        for (int k : new int[] { 3, 5, 8, 10, 12, 15, 20, 25, 30, members }) {
            if (k > members) continue;
            TrackingSettings s = new TrackingSettings();
            s.maxNames = k;
            s.selection = selection;
            out.add(new CurvePoint(k, track(inputs, s).trackingError()));
        }
        return out;
    }

    // Helpers

    /** Weights above 1e-6, rounded to 6 decimals, in asset order. */
    public static Map<String, Double> weightMap(List<String> assets, double[] w) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(assets.size(), w.length); i++) {
            double x = w[i];
            if (x > 1e-6) out.put(assets.get(i), Math.round(x * 1e6) / 1e6);
        }
        return out;
    }

    /** Trades of more than 0.01% of the account, largest first. */
    public static List<Trade> trades(OptimizationInputs inputs, double[] w) {
        List<Trade> out = new ArrayList<>();
        for (int i = 0; i < inputs.n(); i++) {
            if (Math.abs(w[i] - inputs.current[i]) > 1e-4) out.add(new Trade(inputs.assets.get(i), inputs.current[i], w[i]));
        }
        out.sort((x, y) -> Double.compare(Math.abs(y.change()), Math.abs(x.change())));
        return out;
    }

    private static OptimizationResult failed(String status, Decision decision, long started) {
        return new OptimizationResult(NAME, status, Map.of(), 0, null, null, null, 0, 0, 0, List.of(), Map.of(), List.of(),
                decision, null, null, null, millis(started));
    }

    private static double[] minus(double[] x, double[] y) {
        double[] out = new double[Math.min(x.length, y.length)];
        for (int i = 0; i < out.length; i++) out[i] = x[i] - y[i];
        return out;
    }

    private static int countHeld(double[] w) {
        int c = 0;
        for (double x : w) if (x > 1e-6) c++;
        return c;
    }

    private static double millis(long started) { return (System.nanoTime() - started) / 1e6; }

    // Risk

    /** Volatility, VaR and expected shortfall, tracking error, beta, risk contributions of weights w (in asset order). */
    public static RiskReport risk(OptimizationInputs inputs, double[] w) {
        int n = inputs.n();
        double[] b = inputs.benchmark;
        double[] sw = inputs.mul(w);
        double variance = Math.max(dot(w, sw), 1e-16);
        double vol = Math.sqrt(variance);
        double bVar = Math.max(inputs.quad(b), 1e-16);
        double[] act = minus(w, b);
        double[] sa = inputs.mul(act);
        double aVar = Math.max(dot(act, sa), 1e-16);
        double beta = dot(w, inputs.mul(b)) / bVar;
        double mu = 0, rate = 0, dr = 0;
        for (int i = 0; i < n; i++) {
            Security sec = Universe.security(inputs.assets.get(i));
            mu += w[i] * (sec != null ? Universe.expectedReturn(sec) : 0);
            rate += w[i] * (sec != null ? sec.rateBeta : 0);
            dr += w[i] * Math.sqrt(inputs.cov[i * n + i]);
        }
        dr /= vol;
        double h = 21.0 / 252, z = 1.6449;
        double sigmaH = vol * Math.sqrt(h), muH = mu * h;
        double phi = Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI);
        List<RiskReport.Contribution> contributions = new ArrayList<>();
        Map<String, double[]> bySector = new LinkedHashMap<>();
        List<RiskReport.Contribution> active = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String asset = inputs.assets.get(i), sector = inputs.sectors.get(i);
            double share = w[i] * sw[i] / variance;
            if (w[i] > 1e-6) contributions.add(new RiskReport.Contribution(asset, w[i], share));
            double[] ws = bySector.computeIfAbsent(sector, k -> new double[2]);
            ws[0] += w[i];
            ws[1] += share;
            double ashare = act[i] * sa[i] / aVar;
            if (Math.abs(act[i]) > 1e-6) active.add(new RiskReport.Contribution(asset, act[i], ashare));
        }
        List<RiskReport.Contribution> sectors = new ArrayList<>();
        for (Map.Entry<String, double[]> e : bySector.entrySet()) {
            if (e.getValue()[0] > 1e-6) sectors.add(new RiskReport.Contribution(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        java.util.Comparator<RiskReport.Contribution> byShare = (x, y) -> Double.compare(y.share(), x.share());
        contributions.sort(byShare);
        sectors.sort(byShare);
        active.sort(byShare);
        double concentration = 0;
        for (double x : w) concentration += x * x;
        return new RiskReport(vol, Math.sqrt(bVar), Math.sqrt(aVar), beta,
                Math.max(0, z * sigmaH - muH), Math.max(0, sigmaH * phi / 0.05 - muH),
                mu, rate * 0.01, dr, concentration, contributions, sectors, active);
    }

    private static double dot(double[] x, double[] y) {
        double s = 0;
        for (int i = 0, m = Math.min(x.length, y.length); i < m; i++) s += x[i] * y[i];
        return s;
    }
}
