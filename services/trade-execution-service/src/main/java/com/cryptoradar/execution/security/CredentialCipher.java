package com.cryptoradar.execution.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * AES-GCM encryption for exchange API credentials stored at rest.
 *
 * <p>Ciphertext format: <code>base64(IV(12 bytes) || ciphertext-with-gcm-tag)</code>.
 * The IV is random per encrypt call. The GCM tag (16 bytes) is appended by the
 * cipher after the encrypted content.
 *
 * <p>Master key must be a 32-byte AES key, provided base64-encoded via the
 * {@code execution.master-key} config. Optionally a second {@code
 * execution.master-key-prev} value can be provided during key rotation — the
 * cipher tries the primary key first and falls back to the previous key if
 * decryption fails (authentication tag mismatch).
 *
 * <p>Plaintext never enters a field, never gets logged. Callers must not pass
 * plaintext to a method that serializes (toString, log formatters, exceptions).
 */
@ApplicationScoped
public class CredentialCipher {

    private static final int AES_KEY_BYTES = 32;   // AES-256
    private static final int GCM_IV_BYTES = 12;    // 96-bit IV (GCM recommendation)
    private static final int GCM_TAG_BITS = 128;   // 16-byte tag
    private static final String CIPHER_SPEC = "AES/GCM/NoPadding";

    private final SecretKeySpec primaryKey;
    private final SecretKeySpec previousKey;  // may be null
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(
            @ConfigProperty(name = "execution.master-key") String masterKeyBase64,
            @ConfigProperty(name = "execution.master-key-prev", defaultValue = "") String prevKeyBase64) {
        this.primaryKey = loadKey(masterKeyBase64, "execution.master-key");
        this.previousKey = (prevKeyBase64 == null || prevKeyBase64.isBlank())
                ? null
                : loadKey(prevKeyBase64, "execution.master-key-prev");
    }

    private static SecretKeySpec loadKey(String b64, String configName) {
        if (b64 == null || b64.isBlank()) {
            throw new IllegalArgumentException(configName + " is required (base64-encoded 32-byte AES key)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(configName + " is not valid base64", e);
        }
        if (raw.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException(
                    configName + " must decode to " + AES_KEY_BYTES + " bytes (got " + raw.length + ")");
        }
        return new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_SPEC);
            cipher.init(Cipher.ENCRYPT_MODE, primaryKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plainBytes);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            Arrays.fill(plainBytes, (byte) 0);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Don't leak plaintext size or content
            throw new RuntimeException("encrypt failed", e);
        }
    }

    public String decrypt(String ciphertextBase64) {
        byte[] blob = Base64.getDecoder().decode(ciphertextBase64);
        if (blob.length < GCM_IV_BYTES + 16) {
            throw new IllegalArgumentException("ciphertext too short to contain IV + GCM tag");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        byte[] ct = new byte[blob.length - GCM_IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, GCM_IV_BYTES);
        System.arraycopy(blob, GCM_IV_BYTES, ct, 0, ct.length);

        return tryDecrypt(primaryKey, iv, ct)
                .or(() -> previousKey == null ? Optional.empty() : tryDecrypt(previousKey, iv, ct))
                .orElseThrow(() -> new RuntimeException("decrypt failed under current and previous keys"));
    }

    private Optional<String> tryDecrypt(SecretKeySpec key, byte[] iv, byte[] ct) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_SPEC);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            String s = new String(plain, StandardCharsets.UTF_8);
            Arrays.fill(plain, (byte) 0);
            return Optional.of(s);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
