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

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(OrderReconcilerTest.Profile.class)
class OrderReconcilerTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            String keyB64 = Base64.getEncoder().encodeToString(k);
            return Map.ofEntries(
                    Map.entry("bybit.rest-base-override.DEMO", "http://localhost:38102"),
                    Map.entry("bybit.rest-base-override.MAINNET", "http://localhost:38102"),
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

    @Inject OrderReconciler reconciler;
    @Inject ObjectMapper mapper;
    @Inject CredentialCipher cipher;
    @Inject ExchangeAccountRepository accountRepo;
    @Inject ExecutedTradeRepository tradeRepo;
    @Inject ExecutionEventRepository eventRepo;

    ExchangeAccount account;

    @BeforeEach
    @Transactional
    void setup() {
        wireMock = new WireMockServer(38102);
        wireMock.start();
        WireMock.configureFor("localhost", 38102);
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();

        account = new ExchangeAccount();
        account.setExchange("BYBIT");
        account.setEnvironment("DEMO");
        account.setApiKeyEncrypted(cipher.encrypt("k"));
        account.setApiSecretEncrypted(cipher.encrypt("s"));
        accountRepo.persist(account);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        eventRepo.deleteAll();
        tradeRepo.deleteAll();
        accountRepo.deleteAll();
        wireMock.stop();
    }

    private void stubPositionsEmpty() throws Exception {
        stubFor(get(urlPathEqualTo("/v5/position/list"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of()),
                        "time", 1700000000000L)))));
    }

    private void stubPositionsWithBtcLong() throws Exception {
        Map<String, Object> position = Map.ofEntries(
                Map.entry("symbol", "BTCUSDT"),
                Map.entry("side", "Buy"),
                Map.entry("positionIdx", 0),
                Map.entry("size", "0.001"),
                Map.entry("avgPrice", "50000"),
                Map.entry("leverage", "3"),
                Map.entry("stopLoss", "49500"),
                Map.entry("takeProfit", "52000"),
                Map.entry("unrealisedPnl", "10"),
                Map.entry("createdTime", "1700000000000"),
                Map.entry("updatedTime", "1700000000000"));
        stubFor(get(urlPathEqualTo("/v5/position/list"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(position)),
                        "time", 1700000000000L)))));
    }

    private void stubClosedPnlWithEntry() throws Exception {
        Map<String, Object> entry = Map.ofEntries(
                Map.entry("symbol", "BTCUSDT"),
                Map.entry("orderId", "CLOSE-1"),
                Map.entry("side", "Sell"),
                Map.entry("qty", "0.001"),
                Map.entry("orderPrice", "51000"),
                Map.entry("closedPnl", "1.0"),
                Map.entry("openFee", "0.025"),
                Map.entry("closeFee", "0.025"),
                Map.entry("createdTime", "1700000100000"),
                Map.entry("updatedTime", "1700000100000"));
        stubFor(get(urlPathEqualTo("/v5/position/closed-pnl"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(entry)),
                        "time", 1700000100000L)))));
    }

    /**
     * Bybit can omit openFee/closeFee on liquidation or partial-fill edge cases.
     * Leaving the fields out of the JSON entirely deserializes to null on the DTO record.
     */
    private void stubClosedPnlMissingFees() throws Exception {
        Map<String, Object> entry = Map.ofEntries(
                Map.entry("symbol", "BTCUSDT"),
                Map.entry("orderId", "CLOSE-2"),
                Map.entry("side", "Sell"),
                Map.entry("qty", "0.001"),
                Map.entry("orderPrice", "51000"),
                Map.entry("closedPnl", "1.0"),
                Map.entry("createdTime", "1700000100000"),
                Map.entry("updatedTime", "1700000100000"));
        stubFor(get(urlPathEqualTo("/v5/position/closed-pnl"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(entry)),
                        "time", 1700000100000L)))));
    }

    @Test
    @Transactional
    void closesLocalRowWhenRemoteHasNoMatchingPosition() throws Exception {
        // Seed a local OPEN row, stub Bybit to show no positions.
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.OPEN);
        t.setEntryPrice(new BigDecimal("50000"));
        t.setStopPrice(new BigDecimal("49500"));
        t.setTargetPrice(new BigDecimal("52000"));
        t.setQty(new BigDecimal("0.001"));
        t.setExchangeOrderLinkId("ex-test-1");
        tradeRepo.persist(t);

        stubPositionsEmpty();
        stubClosedPnlWithEntry();

        reconciler.reconcileAccount(account);

        ExecutedTrade refreshed = tradeRepo.findById(t.getId());
        assertEquals(TradeStatus.CLOSED, refreshed.getStatus());
        assertEquals(0, new BigDecimal("1.0").compareTo(refreshed.getRealizedPnlUsdt()));
        assertTrue(refreshed.getFeesUsdt().signum() > 0);
        // R-math branch: entry 50000, stop 49500, exit 51000 → (51000-50000)/500 = 2.0R
        assertNotNull(refreshed.getRealizedRMultiple());
        assertEquals(0, new BigDecimal("2.0000").compareTo(refreshed.getRealizedRMultiple()));
    }

    @Test
    @Transactional
    void closesWithZeroFeesWhenBybitOmitsFeeFields() throws Exception {
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.OPEN);
        t.setEntryPrice(new BigDecimal("50000"));
        t.setStopPrice(new BigDecimal("49500"));
        t.setTargetPrice(new BigDecimal("52000"));
        t.setQty(new BigDecimal("0.001"));
        t.setExchangeOrderLinkId("ex-test-2");
        tradeRepo.persist(t);

        stubPositionsEmpty();
        stubClosedPnlMissingFees();

        // Should NOT throw NPE even though openFee/closeFee are null on the DTO.
        reconciler.reconcileAccount(account);

        ExecutedTrade refreshed = tradeRepo.findById(t.getId());
        assertEquals(TradeStatus.CLOSED, refreshed.getStatus());
        assertNotNull(refreshed.getFeesUsdt());
        assertEquals(0, BigDecimal.ZERO.compareTo(refreshed.getFeesUsdt()));
    }

    @Test
    @Transactional
    void createsOrphanWhenBybitHasPositionNotTrackedLocally() throws Exception {
        stubPositionsWithBtcLong();

        reconciler.reconcileAccount(account);

        List<ExecutedTrade> all = tradeRepo.listAll();
        assertEquals(1, all.size());
        ExecutedTrade orphan = all.get(0);
        assertEquals("BTCUSDT", orphan.getSymbol());
        assertEquals("LONG", orphan.getDirection());
        assertEquals(TradeStatus.OPEN, orphan.getStatus());
    }
}
