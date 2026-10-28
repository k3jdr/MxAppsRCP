package com.k3jdr.mxjobsmgr.engine;

import java.util.Map;
import java.util.TreeMap;

import com.k3jdr.mx.core.secure.Secrets;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;

/**
 * API subscription keys live in MxAcctMGR ▸ Banking ▸ Secure Accounts: a subscription names a secure account and its
 * calls send that account's service key, which MxAcctMGR keeps in Eclipse secure storage (Windows DPAPI) at
 * {@code /com.k3jdr.mx/secure-accounts/<id>}, key {@code service key}. Keys are never written to jobspace files, and
 * MxJobsMGR has no place to type one. Whether a key is there is answered without decrypting it.
 */
public final class SecureKeys {
    private SecureKeys() {}

    public static final String KEY = "service key";

    private static String path(int id) {
        return Secrets.path(Secrets.SECURE_ACCOUNTS, Integer.toString(id));
    }

    /** The service key of the subscription's secure account, sent with its calls (decrypted; call off the UI thread). */
    public static String key(ApiSubscription sub) {
        return sub.secureAccountID == null ? null : key(sub.secureAccountID);
    }

    public static String key(int id) {
        return Secrets.get(path(id), KEY).filter(k -> !k.isEmpty()).orElse(null);
    }

    public static boolean hasKey(ApiSubscription sub) {
        return sub.secureAccountID != null && hasKey(sub.secureAccountID);
    }

    /** Whether the account has a service key stored, without decrypting it. */
    public static boolean hasKey(int id) {
        return Secrets.has(path(id), KEY);
    }

    /**
     * The secure accounts that have a service key, by id, with the name MxAcctMGR stored beside it when it stored one
     * unencrypted: the picker's list when the accounts service isn't running.
     */
    public static Map<Integer, String> stored() {
        Map<Integer, String> out = new TreeMap<>();
        for (String child : Secrets.children(Secrets.path(Secrets.SECURE_ACCOUNTS))) {
            int id;
            try {
                id = Integer.parseInt(child);
            } catch (NumberFormatException e) {
                continue;     // not an account node
            }
            if (!hasKey(id)) continue;
            String name = Secrets.getUnencrypted(path(id), "name").filter(n -> !n.isBlank()).orElse("Secure account " + id);
            out.put(id, name);
        }
        return out;
    }
}
