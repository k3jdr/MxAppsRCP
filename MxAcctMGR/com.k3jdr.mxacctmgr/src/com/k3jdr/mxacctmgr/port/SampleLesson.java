package com.k3jdr.mxacctmgr.port;

import java.util.List;

/** What a sample account teaches; trading is "buyAndHold", or "trader" (bought and sold every few weeks). */
public record SampleLesson(String code, String symbol, PortfolioStyle style, String trading, String lesson, List<String> lookFor) {
    public SampleLesson {
        lookFor = List.copyOf(lookFor);
    }

    public SampleLesson(String code, String symbol, PortfolioStyle style, String lesson, List<String> lookFor) {
        this(code, symbol, style, "buyAndHold", lesson, lookFor);
    }
}
