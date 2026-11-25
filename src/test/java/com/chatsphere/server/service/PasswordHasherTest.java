package com.chatsphere.server.service;

import com.chatsphere.server.security.PasswordHasher;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void hash_has_the_documented_format() {
        String h = PasswordHasher.hash("password1");
        String[] parts = h.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2", parts[0]);
        assertEquals("120000", parts[1]);
        assertEquals(16, Base64.getDecoder().decode(parts[2]).length, "16-byte salt");
        assertEquals(32, Base64.getDecoder().decode(parts[3]).length, "256-bit hash");
        assertFalse(h.contains("password1"));
    }

    @Test
    void same_password_gets_different_salts_and_hashes() {
        String a = PasswordHasher.hash("password1");
        String b = PasswordHasher.hash("password1");
        assertNotEquals(a, b);
        assertTrue(PasswordHasher.verify("password1", a));
        assertTrue(PasswordHasher.verify("password1", b));
    }

    @Test
    void verify_accepts_right_and_rejects_wrong_passwords() {
        String h = PasswordHasher.hash("correct horse battery staple");
        assertTrue(PasswordHasher.verify("correct horse battery staple", h));
        assertFalse(PasswordHasher.verify("correct horse battery stapl", h));
        assertFalse(PasswordHasher.verify("", h));
        assertFalse(PasswordHasher.verify("CORRECT HORSE BATTERY STAPLE", h));
    }

    @Test
    void unicode_and_long_passwords_work() {
        String pw = "pässwörd-日本語-🔒";
        assertTrue(PasswordHasher.verify(pw, PasswordHasher.hash(pw)));
        String longPw = "x".repeat(100);
        assertTrue(PasswordHasher.verify(longPw, PasswordHasher.hash(longPw)));
    }

    @Test
    void malformed_stored_values_never_match_and_never_throw() {
        assertFalse(PasswordHasher.verify("password1", null));
        assertFalse(PasswordHasher.verify(null, PasswordHasher.hash("password1")));
        assertFalse(PasswordHasher.verify("password1", ""));
        assertFalse(PasswordHasher.verify("password1", "garbage"));
        assertFalse(PasswordHasher.verify("password1", "pbkdf2$abc$AAAA$AAAA"));
        assertFalse(PasswordHasher.verify("password1", "pbkdf2$120000$!!!$!!!"));
        assertFalse(PasswordHasher.verify("password1", "md5$120000$AAAA$AAAA"));
        assertFalse(PasswordHasher.verify("password1", "pbkdf2$0$AAAA$AAAA"));
        assertFalse(PasswordHasher.verify("password1", "pbkdf2$999999999$AAAA$AAAA"), "absurd iteration counts are refused");
    }

    @Test
    void verify_uses_the_iteration_count_stored_with_the_hash() throws Exception {
        // a hash written with an older, lower iteration count must still verify
        byte[] salt = new byte[16];
        PBEKeySpec spec = new PBEKeySpec("password1".toCharArray(), salt, 1000, 256);
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        String stored = "pbkdf2$1000$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
        assertTrue(PasswordHasher.verify("password1", stored));
        assertFalse(PasswordHasher.verify("password2", stored));
    }

    @Test
    void dummyVerify_runs_without_error_and_costs_about_one_real_verification() {
        String h = PasswordHasher.hash("password1");
        PasswordHasher.dummyVerify(); // warm up
        PasswordHasher.verify("password1", h);
        long t0 = System.nanoTime();
        for (int i = 0; i < 3; i++) PasswordHasher.dummyVerify();
        long dummy = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < 3; i++) PasswordHasher.verify("wrong-pass", h);
        long real = System.nanoTime() - t0;
        // same order of magnitude (generous bounds so this is not flaky)
        assertTrue(dummy > real / 4 && dummy < real * 4, "dummy=" + dummy + " real=" + real);
    }
}
