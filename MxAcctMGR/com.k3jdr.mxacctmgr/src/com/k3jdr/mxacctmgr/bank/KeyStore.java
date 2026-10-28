package com.k3jdr.mxacctmgr.bank;

import java.util.List;

import com.k3jdr.mx.core.secure.Secrets;

/**
 * Where Banking keeps secrets on this PC (the Mac's Keychain items SecureSecrets and TargetSecrets): Eclipse secure
 * storage, encrypted for the Windows user, never the security master.
 *
 * <pre>
 *   /com.k3jdr.mx/secure-accounts/&lt;id&gt;                 service key, product key, password, name (unencrypted)
 *                                                      (MxJobsMGR reads "service key" and lists the names)
 *   /com.k3jdr.mx/MxAcctMGR/target-accounts/&lt;id&gt;       password, user keys
 * </pre>
 */
public final class KeyStore {
    private KeyStore() {}

    public static final String SERVICE_KEY = "service key", PRODUCT_KEY = "product key", PASSWORD = "password", USER_KEYS = "user keys", NAME = "name";

    public static String secure(int id) { return Secrets.path(Secrets.SECURE_ACCOUNTS, String.valueOf(id)); }

    public static String target(int id) { return Secrets.path("MxAcctMGR", "target-accounts", String.valueOf(id)); }

    /** The value (read only when an editor opens), or "". */
    public static String get(String path, String key) { return Secrets.get(path, key).orElse(""); }

    /** Saves it (empty removes it); answers whether secure storage took it. */
    public static boolean set(String path, String key, String value) {
        if (value == null || value.isEmpty()) {
            Secrets.remove(path, key);
            return true;
        }
        return Secrets.put(path, key, value);
    }

    /**
     * Keeps the secure account's name beside its keys, unencrypted, so MxJobsMGR's API Subscriptions can list the
     * accounts that have a service key — by name — without decrypting anything, also while the accounts service is down.
     */
    public static void setName(int id, String name) {
        if (name != null && !name.isBlank()) Secrets.put(secure(id), NAME, name, false);
    }

    /** Whether it is stored, without decrypting it (the Keys column). */
    public static boolean has(String path, String key) {
        return Secrets.has(path, key);
    }

    /** Which of a secure account's keys and password are stored: "service key, product key, password". */
    public static String storedKeys(int id) {
        String path = secure(id);
        return String.join(", ", List.of(SERVICE_KEY, PRODUCT_KEY, PASSWORD).stream().filter(k -> has(path, k)).toList());
    }
}
