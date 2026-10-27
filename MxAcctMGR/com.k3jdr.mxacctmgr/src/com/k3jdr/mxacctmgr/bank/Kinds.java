package com.k3jdr.mxacctmgr.bank;

/** A checking entry's kinds (EntryForm.kinds) and the register's short marks for them. */
public final class Kinds {
    private Kinds() {}

    public static final String[][] KINDS = {
        { "check", "Check" }, { "debit", "Debit / card" }, { "deposit", "Deposit" }, { "transfer", "Transfer" },
        { "fee", "Bank fee" }, { "interest", "Interest" } };

    public static int index(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i][0].equals(kind)) return i;
        return -1;
    }

    /** The Num column's mark for an entry without a check number. */
    public static String label(String kind) {
        return switch (kind == null ? "" : kind) {
            case "debit" -> "DEB";
            case "deposit" -> "DEP";
            case "transfer" -> "XFR";
            case "fee" -> "FEE";
            case "interest" -> "INT";
            default -> kind == null ? "" : kind.toUpperCase();
        };
    }
}
