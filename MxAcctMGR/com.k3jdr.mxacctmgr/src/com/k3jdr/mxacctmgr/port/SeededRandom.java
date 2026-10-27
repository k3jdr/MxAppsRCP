package com.k3jdr.mxacctmgr.port;

/** A small, fast, seedable random-number generator (SplitMix64), so every simulation is repeatable. */
public final class SeededRandom {
    private long state;

    public SeededRandom(long seed) { state = seed + 0x9E3779B97F4A7C15L; }

    public long next() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in [0, 1). */
    public double uniform() { return (next() >>> 11) / (double) (1L << 53); }

    /** A standard normal draw (Box–Muller). */
    public double normal() {
        double u1 = Math.max(uniform(), 1e-12), u2 = uniform();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}
