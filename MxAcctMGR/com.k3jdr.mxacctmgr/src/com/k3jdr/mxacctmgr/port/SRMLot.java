package com.k3jdr.mxacctmgr.port;

/** A tax lot of a security-master account, with its security's fields. */
public record SRMLot(
        int securityId,
        String ticker,
        String mic,
        String name,
        String securityType,
        String assetClass,
        String sector,
        String industry,
        String kind,
        Double price,
        String priceDate,
        Double beta,
        Double marketCap,
        Double expenseRatio,
        Integer lotId,
        String acquired,
        double quantity,
        double costBasis,
        String note,
        Double marketValue) {

    public SRMSecurity security() {
        return new SRMSecurity(securityId, ticker, mic, name, securityType, assetClass, sector, industry, null, null, kind,
                price, priceDate, beta, marketCap, null, expenseRatio);
    }
}
