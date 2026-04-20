package com.cryptoradar.execution.resource;

import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(AccountResourceTest.Profile.class)
class AccountResourceTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String keyB64 = Base64.getEncoder().encodeToString(k);
            return Map.ofEntries(
                    Map.entry("bybit.rest-base-override.DEMO", "http://localhost:38100"),
                    Map.entry("bybit.rest-base-override.MAINNET", "http://localhost:38100"),
                    Map.entry("execution.master-key", keyB64),
                    Map.entry("execution.master-key-prev", keyB64),
                    Map.entry("execution.mainnet.enabled", "false"),
                    // Use the running TimescaleDB container that already has execution-init.sql applied.
                    Map.entry("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:31432/marketdata"),
                    Map.entry("quarkus.datasource.username", "cryptoradar"),
                    Map.entry("quarkus.datasource.password", "cryptoradar_ts_pass")
            );
        }
    }

    WireMockServer wireMock;

    @Inject ObjectMapper mapper;
    @Inject ExchangeAccountRepository accountRepo;

    @BeforeEach
    @Transactional
    void setup() {
        wireMock = new WireMockServer(38100);
        wireMock.start();
        WireMock.configureFor("localhost", 38100);
        // Clean DB state
        accountRepo.deleteAll();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        accountRepo.deleteAll();
        wireMock.stop();
    }

    private void stubValidKey() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of(
                                "id", "K1",
                                "apiKey", "demo-key",
                                "readOnly", 0,
                                "permissions", Map.of(
                                        "Derivatives", List.of("Order", "Position"),
                                        "Withdraw", List.of()
                                )
                        ),
                        "time", 1700000000000L
                )))));
    }

    private void stubKeyWithWithdraw() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/user/query-api"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of(
                                "id", "K2",
                                "apiKey", "bad-key",
                                "readOnly", 0,
                                "permissions", Map.of(
                                        "Derivatives", List.of("Order", "Position"),
                                        "Withdraw", List.of("Asset")
                                )
                        ),
                        "time", 1700000000000L
                )))));
    }

    @Test
    void createAccountHappyPath() throws Exception {
        stubValidKey();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "demo-key-long", "apiSecret", "demo-sec"))
                .when().post("/api/execution/accounts")
                .then()
                .statusCode(201)
                .body("exchange", equalTo("BYBIT"))
                .body("environment", equalTo("DEMO"))
                .body("autoTradeEnabled", equalTo(false))
                .body("killSwitch", equalTo(true))
                .body("keyMask", endsWith("long"));
    }

    @Test
    void rejectMainnetWhenDisabled() throws Exception {
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "MAINNET",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then()
                .statusCode(400)
                .body("error", containsString("MAINNET environment is disabled"));
    }

    @Test
    void rejectWithdrawPermissionKey() throws Exception {
        stubKeyWithWithdraw();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then()
                .statusCode(400)
                .body("error", containsString("withdraw permission"));
    }

    @Test
    void rejectDuplicate() throws Exception {
        stubValidKey();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(201);

        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(409);
    }

    @Test
    void listReturnsAll() throws Exception {
        stubValidKey();
        given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts").then().statusCode(201);

        given().when().get("/api/execution/accounts")
                .then().statusCode(200).body("size()", equalTo(1));
    }

    @Test
    void patchUpdatesFields() throws Exception {
        stubValidKey();
        int id = given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(201).extract().path("id");

        given().contentType("application/json")
                .body(Map.of("killSwitch", false, "autoTradeEnabled", true))
                .when().patch("/api/execution/accounts/" + id)
                .then().statusCode(200)
                .body("killSwitch", equalTo(false))
                .body("autoTradeEnabled", equalTo(true));
    }

    @Test
    void deleteWorksWhenNoOpenTrades() throws Exception {
        stubValidKey();
        int id = given().contentType("application/json")
                .body(Map.of("exchange", "BYBIT", "environment", "DEMO",
                        "apiKey", "k", "apiSecret", "s"))
                .when().post("/api/execution/accounts")
                .then().statusCode(201).extract().path("id");

        given().when().delete("/api/execution/accounts/" + id)
                .then().statusCode(204);
    }

    @Test
    void get404WhenMissing() {
        given().when().get("/api/execution/accounts/999999")
                .then().statusCode(404);
    }
}
