package com.cryptoradar.execution.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialCipherTest {

    private static String freshKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void roundTripProducesOriginalPlaintext() {
        String key = freshKey();
        CredentialCipher cipher = new CredentialCipher(key, null);
        String plain = "sk_test_51AbCdEfGhIjKlMnOpQrStUvWxYz";
        String ct = cipher.encrypt(plain);
        assertEquals(plain, cipher.decrypt(ct));
    }

    @Test
    void twoEncryptionsOfSamePlaintextAreDifferent() {
        // IV is random per call — ciphertext should differ
        String key = freshKey();
        CredentialCipher cipher = new CredentialCipher(key, null);
        String ct1 = cipher.encrypt("secret");
        String ct2 = cipher.encrypt("secret");
        assertNotEquals(ct1, ct2);
        assertEquals("secret", cipher.decrypt(ct1));
        assertEquals("secret", cipher.decrypt(ct2));
    }

    @Test
    void decryptFailsWithWrongKey() {
        CredentialCipher cipher1 = new CredentialCipher(freshKey(), null);
        CredentialCipher cipher2 = new CredentialCipher(freshKey(), null);
        String ct = cipher1.encrypt("top-secret");
        assertThrows(RuntimeException.class, () -> cipher2.decrypt(ct));
    }

    @Test
    void previousKeyFallbackWorks() {
        // Encrypt under old key, decrypt under rotated cipher (new primary + old as prev)
        String oldKey = freshKey();
        String newKey = freshKey();
        CredentialCipher oldCipher = new CredentialCipher(oldKey, null);
        String ct = oldCipher.encrypt("rotated-secret");

        CredentialCipher rotated = new CredentialCipher(newKey, oldKey);
        assertEquals("rotated-secret", rotated.decrypt(ct));
    }

    @Test
    void ciphertextHasIvPrependedAndIsBase64() {
        String key = freshKey();
        CredentialCipher cipher = new CredentialCipher(key, null);
        String ct = cipher.encrypt("something");
        byte[] raw = Base64.getDecoder().decode(ct);
        // 12-byte IV + at least (plaintext + 16-byte GCM tag) bytes
        assertTrue(raw.length >= 12 + "something".getBytes().length + 16);
    }

    @Test
    void missingMasterKeyRejectsConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher(null, null));
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher("", null));
    }

    @Test
    void invalidBase64MasterKeyRejects() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialCipher("!!not-base64!!", null));
    }

    @Test
    void masterKeyWrongLengthRejects() {
        // 16 bytes is AES-128 — we require AES-256 (32 bytes)
        byte[] shortKey = new byte[16];
        new SecureRandom().nextBytes(shortKey);
        assertThrows(IllegalArgumentException.class,
                () -> new CredentialCipher(Base64.getEncoder().encodeToString(shortKey), null));
    }
}
