package com.k3jdr.mxacctmgr.port;

/** Attribution methods the analytics service offers. */
public final class AttributionEngine {
    private AttributionEngine() {}

    public enum Method {
        BRINSON_FACHLER("brinson-fachler"), BRINSON_HOOD_BEEBOWER("brinson-hood-beebower");

        public final String rawValue;

        Method(String rawValue) { this.rawValue = rawValue; }

        public String title() { return this == BRINSON_FACHLER ? "Brinson-Fachler" : "Brinson-Hood-Beebower"; }

        public static Method fromRawValue(String raw) {
            for (Method m : values()) if (m.rawValue.equals(raw)) return m;
            return null;
        }

        @Override
        public String toString() { return title(); }
    }
}
