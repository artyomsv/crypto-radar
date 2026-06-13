package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.*;
import com.cryptoradar.execution.security.CredentialCipher;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bybit V5 REST client. Handles all signed authenticated calls that the
 * lifecycle code needs plus one unauthenticated smoke endpoint
 * ({@link #getServerTime(String)}).
 *
 * <p>Credentials are stored encrypted in the DB; this client takes ciphertext
 * in, decrypts lazily per call, signs the request, and never logs plaintext.
 *
 * <p>Base URL per environment:
 * <ul>
 *   <li>DEMO → https://api-demo.bybit.com (unless {@code bybit.rest-base-override.DEMO} set — test hook)</li>
 *   <li>MAINNET → https://api.bybit.com (unless {@code bybit.rest-base-override.MAINNET} set — test hook)</li>
 * </ul>
 */
@ApplicationScoped
public class BybitV5RestClient {

    private static final Logger LOG = Logger.getLogger(BybitV5RestClient.class);
    // recv_window is a jitter buffer only — request timestamps come from
    // BybitClock (synced to Bybit server time), not the raw host clock, so
    // large host drift no longer trips retCode=10002. 30s comfortably absorbs
    // network latency and sub-sync-interval jitter without weakening signature
    // security (the timestamp is part of the HMAC payload).
    private static final String RECV_WINDOW = "30000";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    // Bybit "invalid request, please check your server timestamp or recv_window".
    // If we still see this after signing with the synced clock, the offset is
    // stale (e.g. the laptop just resumed) — force a resync and retry once.
    private static final int RETCODE_TIMESTAMP_ERROR = 10002;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;
    @Inject BybitClock clock;

    @ConfigProperty(name = "bybit.rest-base-override.DEMO")
    java.util.Optional<String> demoBaseOverride;

    @ConfigProperty(name = "bybit.rest-base-override.MAINNET")
    java.util.Optional<String> mainnetBaseOverride;

    /** Shape: result = { "list": [...] } for many Bybit list endpoints. */
    public record ListResult<T>(@com.fasterxml.jackson.annotation.JsonProperty("list") java.util.List<T> list) {}

    // =====================================================================
    // Unauthenticated
    // =====================================================================

    public BybitResponse<ServerTimeResult> getServerTime(String environment) {
        URI uri = URI.create(baseFor(environment) + "/v5/market/time");
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        return send(req, "/v5/market/time", simpleType(ServerTimeResult.class));
    }

    // =====================================================================
    // Authenticated GETs
    // =====================================================================

    public BybitResponse<ApiKeyPermissionsV5> queryApiKey(String environment,
                                                           String apiKeyCipher, String apiSecretCipher) {
        return signedGet(new SignedGetSpec(environment, "/v5/user/query-api", "", apiKeyCipher, apiSecretCipher),
                simpleType(ApiKeyPermissionsV5.class));
    }

    public BybitResponse<ListResult<WalletV5>> getWalletBalance(String environment,
                                                                  String apiKeyCipher, String apiSecretCipher) {
        String qs = "accountType=UNIFIED";
        return signedGet(new SignedGetSpec(environment, "/v5/account/wallet-balance", qs, apiKeyCipher, apiSecretCipher),
                listType(WalletV5.class));
    }

    public BybitResponse<ListResult<PositionV5>> getPositionList(String environment,
                                                                   String apiKeyCipher, String apiSecretCipher) {
        String qs = "category=linear&settleCoin=USDT";
        return signedGet(new SignedGetSpec(environment, "/v5/position/list", qs, apiKeyCipher, apiSecretCipher),
                listType(PositionV5.class));
    }

    public BybitResponse<ListResult<ClosedPnlV5>> getClosedPnl(String environment,
                                                                 String apiKeyCipher, String apiSecretCipher,
                                                                 String symbol, int limit) {
        String qs = "category=linear&symbol=" + symbol + "&limit=" + limit;
        return signedGet(new SignedGetSpec(environment, "/v5/position/closed-pnl", qs, apiKeyCipher, apiSecretCipher),
                listType(ClosedPnlV5.class));
    }

    // =====================================================================
    // Authenticated POSTs
    // =====================================================================

    public BybitResponse<Map<String, Object>> setLeverage(String environment,
                                                            String apiKeyCipher, String apiSecretCipher,
                                                            String symbol, int leverage) {
        SetLeverageRequest body = new SetLeverageRequest("linear", symbol,
                String.valueOf(leverage), String.valueOf(leverage));
        return signedPost(new SignedPostSpec(environment, "/v5/position/set-leverage", body, apiKeyCipher, apiSecretCipher),
                mapType());
    }

    public BybitResponse<PlaceOrderResult> placeOrder(String environment,
                                                       String apiKeyCipher, String apiSecretCipher,
                                                       PlaceOrderRequest req) {
        return signedPost(new SignedPostSpec(environment, "/v5/order/create", req, apiKeyCipher, apiSecretCipher),
                simpleType(PlaceOrderResult.class));
    }

    public BybitResponse<Map<String, Object>> setTradingStop(String environment,
                                                               String apiKeyCipher, String apiSecretCipher,
                                                               TradingStopRequest req) {
        return signedPost(new SignedPostSpec(environment, "/v5/position/trading-stop", req, apiKeyCipher, apiSecretCipher),
                mapType());
    }

    public BybitResponse<PlaceOrderResult> cancelOrder(String environment,
                                                        String apiKeyCipher, String apiSecretCipher,
                                                        String symbol, String orderId) {
        Map<String, String> body = Map.of("category", "linear", "symbol", symbol, "orderId", orderId);
        return signedPost(new SignedPostSpec(environment, "/v5/order/cancel", body, apiKeyCipher, apiSecretCipher),
                simpleType(PlaceOrderResult.class));
    }

    // =====================================================================
    // Internals — base URL, signing, execution
    // =====================================================================

    private String baseFor(String environment) {
        java.util.Optional<String> override = switch (environment) {
            case "DEMO" -> demoBaseOverride;
            case "MAINNET" -> mainnetBaseOverride;
            default -> java.util.Optional.empty();
        };
        return override.filter(s -> !s.isBlank())
                .orElseGet(() -> BybitV5Endpoints.restBaseFor(environment));
    }

    private JavaType simpleType(Class<?> resultType) {
        return mapper.getTypeFactory().constructParametricType(BybitResponse.class, resultType);
    }

    private JavaType listType(Class<?> elementType) {
        JavaType list = mapper.getTypeFactory().constructParametricType(ListResult.class, elementType);
        return mapper.getTypeFactory().constructParametricType(BybitResponse.class, list);
    }

    @SuppressWarnings("unchecked")
    private JavaType mapType() {
        return mapper.getTypeFactory().constructParametricType(
                BybitResponse.class,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }

    /** Carrier for a signed GET — keeps the dispatch methods under the param limit. */
    private record SignedGetSpec(String environment, String path, String queryString,
                                 String apiKeyCipher, String apiSecretCipher) {}

    /** Carrier for a signed POST. {@code body} is serialized to the signed payload. */
    private record SignedPostSpec(String environment, String path, Object body,
                                  String apiKeyCipher, String apiSecretCipher) {}

    private <T> BybitResponse<T> signedGet(SignedGetSpec spec, JavaType type) {
        BybitResponse<T> resp = sendSignedGet(spec, type);
        if (resp.retCode() != RETCODE_TIMESTAMP_ERROR) {
            return resp;
        }
        // Synced clock still rejected — offset is stale (likely a host resume).
        // Force a resync and retry once; 10002 means Bybit did nothing, so a
        // GET retry is trivially safe.
        LOG.warnf("retCode=10002 on %s — resyncing Bybit clock and retrying once", spec.path());
        clock.sync();
        return sendSignedGet(spec, type);
    }

    private <T> BybitResponse<T> signedPost(SignedPostSpec spec, JavaType type) {
        BybitResponse<T> resp = sendSignedPost(spec, type);
        if (resp.retCode() != RETCODE_TIMESTAMP_ERROR) {
            return resp;
        }
        // A 10002 is a pre-execution rejection (bad timestamp) — the order was
        // never placed, so re-signing with a fresh timestamp and retrying once
        // is safe. orderLinkId still dedupes any pathological double-send.
        LOG.warnf("retCode=10002 on %s — resyncing Bybit clock and retrying once", spec.path());
        clock.sync();
        return sendSignedPost(spec, type);
    }

    private <T> BybitResponse<T> sendSignedGet(SignedGetSpec spec, JavaType type) {
        String qs = spec.queryString();
        Map<String, String> headers = signedHeaders(spec.apiKeyCipher(), spec.apiSecretCipher(), qs);
        URI uri = URI.create(baseFor(spec.environment()) + spec.path() + (qs.isEmpty() ? "" : "?" + qs));
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        headers.forEach(rb::header);
        return send(rb.build(), spec.path(), type);
    }

    private <T> BybitResponse<T> sendSignedPost(SignedPostSpec spec, JavaType type) {
        String json = writeJson(spec.body());
        Map<String, String> headers = signedHeaders(spec.apiKeyCipher(), spec.apiSecretCipher(), json);
        URI uri = URI.create(baseFor(spec.environment()) + spec.path());
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        headers.forEach(rb::header);
        return send(rb.build(), spec.path(), type);
    }

    private Map<String, String> signedHeaders(String keyCipher, String secretCipher, String payload) {
        String apiKey = cipher.decrypt(keyCipher);
        String apiSecret = cipher.decrypt(secretCipher);
        // Timestamp comes from the drift-corrected clock, not System.currentTimeMillis().
        String ts = String.valueOf(clock.nowMillis());
        String sig = BybitV5Signer.sign(apiSecret, ts, apiKey, RECV_WINDOW, payload);
        Map<String, String> h = new TreeMap<>();
        h.put("X-BAPI-API-KEY", apiKey);
        h.put("X-BAPI-TIMESTAMP", ts);
        h.put("X-BAPI-RECV-WINDOW", RECV_WINDOW);
        h.put("X-BAPI-SIGN", sig);
        return h;
    }

    private <T> BybitResponse<T> send(HttpRequest req, String path, JavaType type) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // Try to extract retMsg from Bybit's error envelope — if that parse fails, fall back to bare status.
                String retMsg = extractRetMsg(resp.body());
                throw new RuntimeException("Bybit " + path + " failed: status=" + resp.statusCode()
                        + (retMsg == null ? "" : ", retMsg=" + retMsg));
            }
            return mapper.readValue(resp.body(), type);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("Bybit %s failed: %s", path, e.getMessage());
            throw new RuntimeException("Bybit call failed: " + path, e);
        }
    }

    private String extractRetMsg(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return mapper.readTree(body).path("retMsg").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }
}
