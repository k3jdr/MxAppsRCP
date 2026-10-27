package com.k3jdr.mxacctmgr.port;

/** The account's tax treatment. */
public enum AccountType {
    TAXABLE("Taxable brokerage", "taxable"),
    RETIREMENT("Retirement (tax-deferred)", "retirement"),
    ROTH("Roth (tax-free)", "roth");

    /** The Swift raw value, shown on screen. */
    public final String title;
    /** The security master's account_type. */
    public final String code;

    AccountType(String title, String code) {
        this.title = title;
        this.code = code;
    }

    public boolean isTaxable() { return this == TAXABLE; }

    public String rawValue() { return title; }

    /** The security master's account_type: "retirement", "roth", anything else taxable. */
    public static AccountType fromCode(String code) {
        return "retirement".equals(code) ? RETIREMENT : "roth".equals(code) ? ROTH : TAXABLE;
    }

    public static AccountType fromRawValue(String raw) {
        for (AccountType t : values()) if (t.title.equals(raw)) return t;
        return null;
    }

    @Override
    public String toString() { return title; }
}
