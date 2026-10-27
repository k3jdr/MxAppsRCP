package com.k3jdr.mxjobsmgr.engine;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;

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
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            String p = path(id);
            if (root == null || !root.nodeExists(p)) return false;
            return Arrays.asList(root.node(p).keys()).contains(KEY);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The secure accounts that have a service key, by id, with the name MxAcctMGR stored beside it when it stored one
     * unencrypted: the picker's list when the accounts service isn't running.
     */
    public static Map<Integer, String> stored() {
        Map<Integer, String> out = new TreeMap<>();
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            String p = Secrets.path(Secrets.SECURE_ACCOUNTS);
            if (root == null || !root.nodeExists(p)) return out;
            ISecurePreferences accounts = root.node(p);
            for (String child : accounts.childrenNames()) {
                try {
                    int id = Integer.parseInt(child);
                    ISecurePreferences node = accounts.node(child);
                    if (!Arrays.asList(node.keys()).contains(KEY)) continue;
                    String name = null;
                    if (Arrays.asList(node.keys()).contains("name") && !node.isEncrypted("name")) name = node.get("name", null);
                    out.put(id, name == null || name.isBlank() ? "Secure account " + id : name);
                } catch (Exception e) {
                    // not an account node
                }
            }
        } catch (RuntimeException e) {
            // no secure storage
        }
        return out;
    }
}
