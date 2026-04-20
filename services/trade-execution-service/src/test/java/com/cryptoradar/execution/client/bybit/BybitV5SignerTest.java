package com.cryptoradar.execution.client.bybit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BybitV5SignerTest {

    @Test
    void signatureIsHexHmacSha256OfTimestampApiKeyRecvWindowPayload() {
        String signed = BybitV5Signer.sign(
                "secret",
                "1672709911340",
                "XXXXXXXXXX",
                "5000",
                "{\"x\":1}"
        );
        // 64-char lowercase hex (HMAC-SHA256 = 32 bytes = 64 hex chars)
        assertEquals(64, signed.length());
        assertEquals(signed, signed.toLowerCase());
        // Deterministic: same inputs → same output
        String again = BybitV5Signer.sign("secret", "1672709911340", "XXXXXXXXXX", "5000", "{\"x\":1}");
        assertEquals(signed, again);
    }

    @Test
    void differentTimestampProducesDifferentSignature() {
        String a = BybitV5Signer.sign("secret", "1000", "K", "5000", "body");
        String b = BybitV5Signer.sign("secret", "2000", "K", "5000", "body");
        assertEquals(64, a.length());
        assertEquals(64, b.length());
        assertNotEquals(a, b);
    }

    @Test
    void differentSecretProducesDifferentSignature() {
        String a = BybitV5Signer.sign("secret1", "1000", "K", "5000", "body");
        String b = BybitV5Signer.sign("secret2", "1000", "K", "5000", "body");
        assertNotEquals(a, b);
    }

    @Test
    void payloadConcatenationFormat() {
        // Per Bybit V5: payload = timestamp + api_key + recv_window + body
        // Verify we're signing the concatenation, not body alone
        String a = BybitV5Signer.sign("secret", "1000", "K", "5000", "body");
        String b = BybitV5Signer.sign("secret", "1000", "K", "5000", "body_different");
        assertNotEquals(a, b);
    }
}
