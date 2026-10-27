package com.k3jdr.mx.core.secure;

import java.util.Optional;

import org.eclipse.equinox.security.storage.EncodingUtils;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

/**
 * Passwords and API keys, in place of the macOS login Keychain: Eclipse secure storage, encrypted with Windows
 * DPAPI for the signed-in user. Every MxAppsRCP app reads the same store, so a service key MxAcctMGR's Secure
 * Accounts keeps is the one MxJobsMGR sends.
 *
 * <pre>
 *   /com.k3jdr.mx/MxDataSQL/user@host:port/database   password
 *   /com.k3jdr.mx/secure-accounts/&lt;id&gt;                service key
 * </pre>
 */
public final class Secrets {
    private Secrets() {}

    public static final String ROOT = "com.k3jdr.mx";

    /** The secure-accounts node every app shares (MxAcctMGR writes, MxJobsMGR reads). */
    public static final String SECURE_ACCOUNTS = "secure-accounts";

    /** A node path under the root; each part may contain '/' (it is encoded). */
    public static String path(String... parts) {
        StringBuilder sb = new StringBuilder("/").append(ROOT);
        for (String part : parts) sb.append('/').append(EncodingUtils.encodeSlashes(part));
        return sb.toString();
    }

    private static ISecurePreferences node(String path) {
        ISecurePreferences root = SecurePreferencesFactory.getDefault();
        if (root == null) throw new IllegalStateException("Secure storage is not available.");
        return root.node(path);
    }

    /** The value stored under {@code path} / {@code key}, if there is one. */
    public static Optional<String> get(String path, String key) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            if (root == null || !root.nodeExists(path)) return Optional.empty();
            return Optional.ofNullable(root.node(path).get(key, null));
        } catch (StorageException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Stores {@code value} encrypted; answers whether it was saved. */
    public static boolean put(String path, String key, String value) {
        try {
            ISecurePreferences node = node(path);
            node.put(key, value, true);
            node.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void remove(String path, String key) {
        try {
            ISecurePreferences root = SecurePreferencesFactory.getDefault();
            if (root == null || !root.nodeExists(path)) return;
            ISecurePreferences node = root.node(path);
            node.remove(key);
            if (node.keys().length == 0 && node.childrenNames().length == 0) node.removeNode();
            root.flush();
        } catch (Exception e) {
            // nothing stored, nothing to remove
        }
    }
}
