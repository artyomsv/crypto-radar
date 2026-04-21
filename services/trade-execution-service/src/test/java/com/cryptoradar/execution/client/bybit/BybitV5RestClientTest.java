package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.*;
import com.cryptoradar.execution.security.CredentialCipher;
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
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WireMock-backed tests for BybitV5RestClient. Overrides the DEMO base URL to
 * point at a local WireMock on port 38099, so the full REST surface can be
 * stubbed without network calls.
 */
@QuarkusTest
@TestProfile(BybitV5RestClientTest.WireMockProfile.class)
class BybitV5RestClientTest {

    public static class WireMockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> cfg = new java.util.HashMap<>();
            cfg.put("bybit.rest-base-override.DEMO", "http://localhost:38099");
            // MAINNET override required to pass Quarkus empty-string validation
            cfg.put("bybit.rest-base-override.MAINNET", "http://localhost:38099");
            cfg.put("execution.master-key", freshKeyStatic());
            // Quarkus rejects empty strings — provide a valid (but unused) prev key
            cfg.put("execution.master-key-prev", freshKeyStatic());
            // Disable DB and Redis so Quarkus boots without real infra
            cfg.put("quarkus.datasource.active", "false");
            cfg.put("quarkus.hibernate-orm.active", "false");
            cfg.put("quarkus.redis.devservices.enabled", "false");
            cfg.put("quarkus.redis.hosts", "redis://localhost:36379");
            return cfg;
        }
    }

    private static String freshKeyStatic() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    static WireMockServer wireMock;

    @Inject BybitV5RestClient client;
    @Inject CredentialCipher cipher;
    @Inject ObjectMapper mapper;

    String apiKeyCipher;
    String apiSecretCipher;

    @BeforeEach
    void setup() {
        wireMock = new WireMockServer(38099);
        wireMock.start();
        WireMock.configureFor("localhost", 38099);
        apiKeyCipher = cipher.encrypt("test-api-key");
        apiSecretCipher = cipher.encrypt("test-api-secret");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void getServerTimeParsesOkResponse() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/market/time"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("timeSecond", "1700000000", "timeNano", "1700000000000000000"),
                        "time", 1700000000000L
                )))));

        BybitResponse<ServerTimeResult> resp = client.getServerTime("DEMO");
        assertTrue(resp.isOk());
        assertEquals("1700000000", resp.result().timeSecond());
    }

    @Test
    void queryApiKeyReturnsPermissions() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/user/query-api"))
                .withHeader("X-BAPI-API-KEY", equalTo("test-api-key"))
                .withHeader("X-BAPI-SIGN", matching("[a-f0-9]{64}"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of(
                                "id", "1234",
                                "apiKey", "test-api-key",
                                "readOnly", 0,
                                "permissions", Map.of(
                                        "ContractTrade", List.of("Order", "Position"),
                                        "Wallet", List.of("AccountTransfer"),
                                        "Withdraw", List.of()
                                )
                        ),
                        "time", 1700000000000L
                )))));

        BybitResponse<ApiKeyPermissionsV5> resp = client.queryApiKey("DEMO", apiKeyCipher, apiSecretCipher);
        assertTrue(resp.isOk());
        assertTrue(resp.result().permissions().containsKey("ContractTrade"));
        assertTrue(resp.result().permissions().get("Withdraw").isEmpty());
    }

    @Test
    void getPositionListReturnsList() throws Exception {
        // Map.of() is limited to 10 pairs; use a helper map for the position row
        Map<String, Object> positionRow = new java.util.LinkedHashMap<>();
        positionRow.put("symbol", "BTCUSDT");
        positionRow.put("side", "Buy");
        positionRow.put("positionIdx", 0);
        positionRow.put("size", "0.001");
        positionRow.put("avgPrice", "50000");
        positionRow.put("leverage", "3");
        positionRow.put("stopLoss", "49000");
        positionRow.put("takeProfit", "52000");
        positionRow.put("unrealisedPnl", "10");
        positionRow.put("createdTime", "1700000000000");
        positionRow.put("updatedTime", "1700000000000");

        stubFor(get(urlPathEqualTo("/v5/position/list"))
                .withQueryParam("category", equalTo("linear"))
                .withQueryParam("settleCoin", equalTo("USDT"))
                .withHeader("X-BAPI-SIGN", matching("[a-f0-9]{64}"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(positionRow)),
                        "time", 1700000000000L
                )))));

        BybitResponse<BybitV5RestClient.ListResult<PositionV5>> resp =
                client.getPositionList("DEMO", apiKeyCipher, apiSecretCipher);
        assertTrue(resp.isOk());
        assertEquals(1, resp.result().list().size());
        assertEquals("BTCUSDT", resp.result().list().get(0).symbol());
    }

    @Test
    void placeOrderSendsJsonBodyAndParsesResult() throws Exception {
        stubFor(post(urlPathEqualTo("/v5/order/create"))
                .withHeader("X-BAPI-SIGN", matching("[a-f0-9]{64}"))
                .withRequestBody(matchingJsonPath("$.symbol", equalTo("BTCUSDT")))
                .withRequestBody(matchingJsonPath("$.side", equalTo("Buy")))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("orderId", "OID-1", "orderLinkId", "ex-1"),
                        "time", 1700000000000L
                )))));

        PlaceOrderRequest req = new PlaceOrderRequest(
                "linear", "BTCUSDT", "Buy", "Market", "0.001",
                "52000", "49000", "Full", "Market", "Market", "ex-1", null);
        BybitResponse<PlaceOrderResult> resp = client.placeOrder("DEMO", apiKeyCipher, apiSecretCipher, req);
        assertTrue(resp.isOk());
        assertEquals("OID-1", resp.result().orderId());
        assertEquals("ex-1", resp.result().orderLinkId());
    }

    @Test
    void setLeverageSendsExpectedBody() throws Exception {
        stubFor(post(urlPathEqualTo("/v5/position/set-leverage"))
                .withRequestBody(matchingJsonPath("$.buyLeverage", equalTo("3")))
                .withRequestBody(matchingJsonPath("$.sellLeverage", equalTo("3")))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK", "result", Map.of(),
                        "time", 1700000000000L
                )))));

        BybitResponse<Map<String, Object>> resp =
                client.setLeverage("DEMO", apiKeyCipher, apiSecretCipher, "BTCUSDT", 3);
        assertTrue(resp.isOk());
    }

    @Test
    void errorResponseIncludesRetMsg() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/market/time"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody(mapper.writeValueAsString(Map.of(
                                "retCode", 10006,
                                "retMsg", "rate limit exceeded"
                        )))));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.getServerTime("DEMO"));
        assertTrue(ex.getMessage().contains("status=500"));
        assertTrue(ex.getMessage().contains("rate limit exceeded"));
    }
}
