package com.chatsphere.server.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2 password hashing.
 * Stored format: {@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}
 * (PBKDF2WithHmacSHA256, 120,000 iterations, 16-byte random salt, 256-bit hash).
 */
public final class PasswordHasher {
    private PasswordHasher() {}

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int ITERATIONS = 120_000;
    public static final int SALT_BYTES = 16;
    public static final int HASH_BITS = 256;
    private static final int MAX_ACCEPTED_ITERATIONS = 5_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Verified against when the user is unknown, so "no such user" costs the same time as "wrong password". */
    private static final String DUMMY_HASH = hash("dummy-password-that-is-never-used");

    /** Returns a new salted hash string for the password. */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] h = derive(password, salt, ITERATIONS, HASH_BITS);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(h);
    }

    /** True when the password matches the stored hash. Malformed stored values simply do not match. */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
            int iterations = Integer.parseInt(p[1]);
            if (iterations < 1 || iterations > MAX_ACCEPTED_ITERATIONS) return false;
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            if (expected.length == 0) return false;
            byte[] actual = derive(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual); // constant time
        } catch (IllegalArgumentException e) { // bad number or bad base64
            return false;
        }
    }

    /** Burns the same CPU as a real verification. Call it when the user does not exist. */
    public static void dummyVerify() {
        verify("not-the-password", DUMMY_HASH);
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int bits) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(chars, '\0');
        }
    }
}
