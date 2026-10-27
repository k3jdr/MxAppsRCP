package com.k3jdr.mxacctmgr.port;

import java.util.List;

/** Assets, their benchmark and current weights, alpha and covariance: mxjobs.inputs/1 in Java. */
public final class OptimizationInputs {
    public final List<String> assets;
    public final List<String> sectors;
    public final double[] benchmark;
    public final double[] current;
    public final double[] alpha;
    /** Row-major n × n, annualised. */
    public final double[] cov;

    public OptimizationInputs(List<String> assets, List<String> sectors, double[] benchmark, double[] current,
                              double[] alpha, double[] cov) {
        this.assets = List.copyOf(assets);
        this.sectors = List.copyOf(sectors);
        this.benchmark = benchmark;
        this.current = current;
        this.alpha = alpha;
        this.cov = cov;
    }

    public static OptimizationInputs empty() {
        return new OptimizationInputs(List.of(), List.of(), new double[0], new double[0], new double[0], new double[0]);
    }

    public int n() { return assets.size(); }

    /** The same inputs with other current weights (unsaved holdings on screen). */
    public OptimizationInputs withCurrent(double[] current) {
        return new OptimizationInputs(assets, sectors, benchmark, current, alpha, cov);
    }

    /** Σx. */
    public double[] mul(double[] x) {
        int n = n();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            int row = i * n;
            for (int j = 0; j < n; j++) s += cov[row + j] * x[j];
            out[i] = s;
        }
        return out;
    }

    /** xᵀΣx. */
    public double quad(double[] x) {
        double[] sx = mul(x);
        double s = 0;
        for (int i = 0, m = Math.min(x.length, sx.length); i < m; i++) s += x[i] * sx[i];
        return s;
    }

    /** Largest eigenvalue of Σ (60 power iterations from the ones vector, as in the services). */
    public double maxEigenvalue() {
        int n = n();
        double[] v = new double[n];
        java.util.Arrays.fill(v, 1 / Math.sqrt(Math.max(n, 1)));
        double lambda = 0;
        for (int k = 0; k < 60; k++) {
            double[] w = mul(v);
            double norm = 0;
            for (double x : w) norm += x * x;
            norm = Math.sqrt(norm);
            if (!(norm > 0)) return 0;
            lambda = norm;
            for (int i = 0; i < n; i++) v[i] = w[i] / norm;
        }
        return lambda;
    }
}
