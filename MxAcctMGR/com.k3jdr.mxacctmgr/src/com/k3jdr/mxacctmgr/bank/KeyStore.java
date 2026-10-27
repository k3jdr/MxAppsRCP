package com.k3jdr.mxacctmgr.bank;

import java.util.Arrays;
import java.util.List;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;

import com.k3jdr.mx.core.secure.Secrets;

/**
 * Where Banking keeps secrets on this PC (the Mac's Keychain items SecureSecrets and TargetSecrets): Eclipse secure
 * storage, encrypted for the Windows user, never the security master.
 *
 * <pre>
 *   /com.k3jdr.mx/secure-accounts/&lt;id&gt;                 service key, product key, password   (MxJobsMGR reads "service key")
 *   /com.k3jdr.mx/MxAcctMGR/target-accounts/&lt;id&gt;       password, user keys
 * </pre>
 */
public final class KeyStore {
    private KeyStore() {}

    public static final String SERVICE_KEY = "service key", PRODUCT_KEY = "product key", PASSWORD = "password", USER_KEYS = "user keys";

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

    /** Whether it is stored, without decrypting it (the Keys column). */
    public static boolean has(String path, String key) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            if (root == null || !root.nodeExists(path)) return false;
            return Arrays.asList(root.node(path).keys()).contains(key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Which of a secure account's keys and password are stored: "service key, product key, password". */
    public static String storedKeys(int id) {
        String path = secure(id);
        return String.join(", ", List.of(SERVICE_KEY, PRODUCT_KEY, PASSWORD).stream().filter(k -> has(path, k)).toList());
    }
}
