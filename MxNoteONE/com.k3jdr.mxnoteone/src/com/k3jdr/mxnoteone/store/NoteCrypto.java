package com.k3jdr.mxnoteone.store;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.SwiftJson;

/**
 * Password protection for sections, as NoteCrypto.swift does it: a 256-bit key from the password with
 * PBKDF2-SHA256 (200,000 rounds, 16-byte salt), files sealed with AES-GCM in CryptoKit's combined layout (12-byte
 * nonce, ciphertext, 16-byte tag), and a verifier — HMAC-SHA256 of "MxNoteONE section key" — to tell a right
 * password from a wrong one.
 */
public final class NoteCrypto {
    private NoteCrypto() {}

    public static final int ROUNDS = 200_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static SecretKey deriveKey(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] verifier(SecretKey key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return mac.doFinal("MxNoteONE section key".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new protection (salt, verifier, hint) and its key. */
    public static Object[] makeProtection(String password, String hint) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        SecretKey key = deriveKey(password, salt);
        JsonObject p = new JsonObject();
        p.addProperty("salt", SwiftJson.base64(salt));
        p.addProperty("verifier", SwiftJson.base64(verifier(key)));
        p.addProperty("hint", hint == null ? "" : hint);
        return new Object[] { p, key };
    }

    /** The key for the password, or null if the password is wrong. */
    public static SecretKey unlock(JsonObject protection, String password) {
        byte[] salt = SwiftJson.unbase64(SwiftJson.str(protection, "salt", ""));
        byte[] expected = SwiftJson.unbase64(SwiftJson.str(protection, "verifier", ""));
        SecretKey key = deriveKey(password, salt);
        return MessageDigest.isEqual(verifier(key), expected) ? key : null;
    }

    public static boolean verifies(JsonObject protection, SecretKey key) {
        return MessageDigest.isEqual(verifier(key), SwiftJson.unbase64(SwiftJson.str(protection, "verifier", "")));
    }

    public static byte[] seal(byte[] data, SecretKey key) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] sealed = c.doFinal(data);
        byte[] out = new byte[12 + sealed.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(sealed, 0, out, 12, sealed.length);
        return out;
    }

    public static byte[] open(byte[] data, SecretKey key) throws GeneralSecurityException {
        if (data.length < 28) throw new GeneralSecurityException("The file is too short to be sealed.");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
        return c.doFinal(data, 12, data.length - 12);
    }
}
