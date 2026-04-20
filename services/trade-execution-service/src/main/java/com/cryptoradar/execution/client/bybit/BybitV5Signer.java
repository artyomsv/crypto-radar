package com.cryptoradar.execution.client.bybit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 request signing per Bybit V5 spec.
 *
 * <p>Signature = HMAC-SHA256(apiSecret, timestamp + apiKey + recvWindow + payload).
 * Payload is the query string (for GET) or raw body JSON (for POST).
 */
public final class BybitV5Signer {

    private BybitV5Signer() {}

    public static String sign(String apiSecret, String timestamp, String apiKey, String recvWindow, String payload) {
        String toSign = timestamp + apiKey + recvWindow + (payload == null ? "" : payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            return toHex(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
