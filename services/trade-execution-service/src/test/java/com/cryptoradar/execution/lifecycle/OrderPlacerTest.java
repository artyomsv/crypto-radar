package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import com.cryptoradar.execution.security.CredentialCipher;
import com.cryptoradar.execution.testutil.LiveDbGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(OrderPlacerTest.Profile.class)
class OrderPlacerTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String keyB64 = Base64.getEncoder().encodeToString(k);
            return Map.ofEntries(
                    Map.entry("bybit.rest-base-override.DEMO", "http://localhost:38101"),
                    Map.entry("bybit.rest-base-override.MAINNET", "http://localhost:38101"),
                    Map.entry("execution.master-key", keyB64),
                    Map.entry("execution.master-key-prev", keyB64),
                    Map.entry("execution.mainnet.enabled", "false")
                    // DB config comes from src/test/resources/application.properties
                    // which turns on Dev Services → throwaway Postgres container.
                    // Never add a quarkus.datasource.jdbc.url override here;
                    // LiveDbGuard fails the class if someone does.
            );
        }
    }

    @BeforeAll
    static void guardAgainstLiveDb() {
        LiveDbGuard.assertNotLiveDb();
    }

    static WireMockServer wireMock;

    @Inject OrderPlacer placer;
    @Inject InstrumentRegistry instruments;
    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;

    ExchangeAccount account;

    @BeforeEach
    @Transactional
    void setup() {
        wireMock = new WireMockServer(38101);
        wireMock.start();
        WireMock.configureFor("localhost", 38101);
        instruments.invalidate();
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
        account = new ExchangeAccount();
        account.setExchange("BYBIT");
        account.setEnvironment("DEMO");
        account.setApiKeyEncrypted(cipher.encrypt("k"));
        account.setApiSecretEncrypted(cipher.encrypt("s"));
        accountRepo.persist(account);

        stubWallet("1000");
        stubSetLeverageOk();
        stubInstrument("BTCUSDT", "0.001");
    }

    @AfterEach
    @Transactional
    void tearDown() {
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
        wireMock.stop();
    }

    private void stubWallet(String equity) {
        try {
            stubFor(get(urlPathEqualTo("/v5/account/wallet-balance"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", 0, "retMsg", "OK",
                            "result", Map.of("list", List.of(Map.of(
                                    "totalEquity", equity,
                                    "totalAvailableBalance", equity,
                                    "totalPerpUPL", "0",
                                    "totalWalletBalance", equity
                            ))),
                            "time", 1700000000000L
                    )))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubSetLeverageOk() {
        try {
            stubFor(post(urlPathEqualTo("/v5/position/set-leverage"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", 0, "retMsg", "OK", "result", Map.of(),
                            "time", 1700000000000L)))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubInstrument(String symbol, String qtyStep) {
        try {
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
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubPlaceOrderOk(String orderId) {
        try {
            stubFor(post(urlPathEqualTo("/v5/order/create"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", 0, "retMsg", "OK",
                            "result", Map.of("orderId", orderId, "orderLinkId", "ex-1"),
                            "time", 1700000000000L)))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void stubPlaceOrderRetcode(int retCode, String retMsg) {
        try {
            stubFor(post(urlPathEqualTo("/v5/order/create"))
                    .willReturn(okJson(mapper.writeValueAsString(Map.of(
                            "retCode", retCode, "retMsg", retMsg,
                            "result", Map.of(),
                            "time", 1700000000000L)))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void placeHappyPathInsertsOpenRow() {
        stubPlaceOrderOk("OID-1");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "trend-continuation", "sig-1",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertNotNull(trade.getId());
        assertEquals(TradeStatus.OPEN, trade.getStatus());
        assertEquals("OID-1", trade.getExchangeOrderId());
    }

    @Test
    void insufficientMarginMarksFailed() {
        stubPlaceOrderRetcode(110007, "insufficient margin");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "t", "s",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertEquals(TradeStatus.FAILED, trade.getStatus());
    }

    @Test
    void duplicateOrderLinkIdTreatedAsSuccess() {
        stubPlaceOrderRetcode(110061, "duplicate order link id");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "t", "s",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertEquals(TradeStatus.OPEN, trade.getStatus());
    }

    @Test
    void unknownErrorMarksFailed() {
        stubPlaceOrderRetcode(10006, "rate limit exceeded");
        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "t", "s",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));
        ExecutedTrade trade = placer.place(account, req);
        assertEquals(TradeStatus.FAILED, trade.getStatus());
    }

    @Test
    void bybitRestExceptionMutatesExistingRowNoOrphan() {
        // A Bybit REST connection drop used to leave an orphan PENDING_PLACE row
        // (from the pre-call persist) AND create a second FAILED row. After the
        // fix, the SAME row should be mutated to FAILED and only one row should
        // exist for this signal_id. Stub overrides the order/create endpoint to
        // a connection-reset fault — wallet, set-leverage, instruments stubs from
        // @BeforeEach remain in place.
        stubFor(post(urlPathEqualTo("/v5/order/create"))
                .willReturn(WireMock.aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        OrderPlacer.PlacementRequest req = new OrderPlacer.PlacementRequest(
                "BTCUSDT", "LONG", "trend-continuation", "sig-orphan-test",
                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("51500"));

        long beforeCount = tradeRepo.count();
        ExecutedTrade trade = placer.place(account, req);
        long afterCount = tradeRepo.count();

        assertEquals(TradeStatus.FAILED, trade.getStatus());
        assertEquals(beforeCount + 1, afterCount,
                "exactly ONE row should be created per failed signal, not two");
        List<ExecutedTrade> bySignal = tradeRepo.find("signalId", "sig-orphan-test").list();
        assertEquals(1, bySignal.size(),
                "must not leave a duplicate row for the same signal_id");
        assertEquals(TradeStatus.FAILED, bySignal.get(0).getStatus());
    }
}
