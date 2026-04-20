package com.cryptoradar.execution.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(InstrumentRegistryTest.Profile.class)
class InstrumentRegistryTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String keyB64 = Base64.getEncoder().encodeToString(k);
            Map<String, String> cfg = new HashMap<>();
            cfg.put("bybit.rest-base-override.DEMO", "http://localhost:38103");
            // Quarkus rejects empty-string ConfigProperty — provide a placeholder for MAINNET too.
            cfg.put("bybit.rest-base-override.MAINNET", "http://localhost:38103");
            cfg.put("execution.master-key", keyB64);
            cfg.put("execution.master-key-prev", keyB64);
            // No DB or Redis needed for a pure HTTP-cache test.
            cfg.put("quarkus.datasource.active", "false");
            cfg.put("quarkus.hibernate-orm.active", "false");
            cfg.put("quarkus.redis.devservices.enabled", "false");
            cfg.put("quarkus.redis.hosts", "redis://localhost:36379");
            return cfg;
        }
    }

    static WireMockServer wireMock;

    @Inject InstrumentRegistry registry;
    @Inject ObjectMapper mapper;

    @BeforeEach
    void setup() {
        wireMock = new WireMockServer(38103);
        wireMock.start();
        WireMock.configureFor("localhost", 38103);
        registry.invalidate();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private void stubInstrument(String symbol, String qtyStep) throws Exception {
        stubFor(get(urlPathEqualTo("/v5/market/instruments-info"))
                .withQueryParam("category", equalTo("linear"))
                .withQueryParam("symbol", equalTo(symbol))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(Map.of(
                                "symbol", symbol,
                                "lotSizeFilter", Map.of("qtyStep", qtyStep)
                        ))),
                        "time", 1700000000000L
                )))));
    }

    @Test
    void fetchesXrpQtyStepAsOne() throws Exception {
        stubInstrument("XRPUSDT", "1");
        assertEquals(1.0, registry.qtyStepFor("DEMO", "XRPUSDT"));
    }

    @Test
    void fetchesBtcQtyStepAsThousandth() throws Exception {
        stubInstrument("BTCUSDT", "0.001");
        assertEquals(0.001, registry.qtyStepFor("DEMO", "BTCUSDT"));
    }

    @Test
    void cachesAfterFirstFetch() throws Exception {
        stubInstrument("SOLUSDT", "0.1");
        assertEquals(0.1, registry.qtyStepFor("DEMO", "SOLUSDT"));
        wireMock.resetRequests();
        assertEquals(0.1, registry.qtyStepFor("DEMO", "SOLUSDT"));
        // second call hits cache — no new HTTP request
        verify(0, getRequestedFor(urlPathEqualTo("/v5/market/instruments-info")));
    }

    @Test
    void fallsBackToStaticTableOnHttpFailure() {
        // no stub — WireMock returns 404 → fall back to FALLBACK_QTY_STEP["DOGEUSDT"] = 1.0
        assertEquals(1.0, registry.qtyStepFor("DEMO", "DOGEUSDT"));
    }

    @Test
    void fallsBackToSmallStepForUnknownSymbol() {
        // unknown symbol, no stub → FALLBACK map returns default 0.001
        assertEquals(0.001, registry.qtyStepFor("DEMO", "WEIRDUSDT"));
    }
}
