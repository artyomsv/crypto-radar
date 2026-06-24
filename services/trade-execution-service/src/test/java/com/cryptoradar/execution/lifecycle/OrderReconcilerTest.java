package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.client.bybit.dto.ClosedPnlV5;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                Map.entry("createdTime", String.valueOf(System.currentTimeMillis())),
                Map.entry("updatedTime", String.valueOf(System.currentTimeMillis())));
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
                Map.entry("createdTime", String.valueOf(System.currentTimeMillis())),
                Map.entry("updatedTime", String.valueOf(System.currentTimeMillis())));
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

    /**
     * Stub the closed-pnl endpoint with the post-fix payload shape: includes
     * the avgEntryPrice / avgExitPrice fields the reconciler now uses to
     * (a) backfill missing entry_price and (b) classify exits via price.
     */
    private void stubClosedPnlFullPayload(String avgEntryPrice, String avgExitPrice,
                                           String closedPnl, String closeSide) throws Exception {
        Map<String, Object> entry = Map.ofEntries(
                Map.entry("symbol", "BTCUSDT"),
                Map.entry("orderId", "CLOSE-3"),
                Map.entry("side", closeSide),
                Map.entry("qty", "0.001"),
                Map.entry("orderPrice", avgExitPrice),
                Map.entry("avgEntryPrice", avgEntryPrice),
                Map.entry("avgExitPrice", avgExitPrice),
                Map.entry("closedPnl", closedPnl),
                Map.entry("openFee", "0.025"),
                Map.entry("closeFee", "0.025"),
                Map.entry("createdTime", String.valueOf(System.currentTimeMillis())),
                Map.entry("updatedTime", String.valueOf(System.currentTimeMillis())));
        stubFor(get(urlPathEqualTo("/v5/position/closed-pnl"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(entry)),
                        "time", 1700000100000L)))));
    }

    @Test
    @Transactional
    void backfillRecoversIncompleteCloseRow() throws Exception {
        // Simulate the WS race: row got marked CLOSED with only qty + closedAt,
        // missing entry_price, exit_price, pnl, fees, exit_reason. Backfill
        // should restore everything from Bybit's closed-pnl payload.
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.CLOSED);
        t.setStopPrice(new BigDecimal("49500"));
        t.setTargetPrice(new BigDecimal("52000"));
        t.setQty(new BigDecimal("0.001"));
        t.setExchangeOrderLinkId("ex-test-bf");
        tradeRepo.persist(t);

        stubClosedPnlFullPayload("50000", "49500", "-5.0", "Sell");

        int recovered = reconciler.backfillIncompleteCloses(account.getId(), 50);

        assertEquals(1, recovered);
        ExecutedTrade refreshed = tradeRepo.findById(t.getId());
        assertNotNull(refreshed.getEntryPrice());
        assertEquals(0, new BigDecimal("50000").compareTo(refreshed.getEntryPrice()));
        assertNotNull(refreshed.getExitPrice());
        assertEquals(0, new BigDecimal("49500").compareTo(refreshed.getExitPrice()));
        assertNotNull(refreshed.getRealizedPnlUsdt());
        assertNotNull(refreshed.getFeesUsdt());
        // exit at stop level + negative pnl + no trail → INITIAL_STOP
        assertEquals(com.cryptoradar.execution.model.ExitReason.INITIAL_STOP, refreshed.getExitReason());
        // R-multiple: (49500-50000)/abs(50000-49500) = -1.0R
        assertNotNull(refreshed.getRealizedRMultiple());
        assertEquals(0, new BigDecimal("-1.0000").compareTo(refreshed.getRealizedRMultiple()));
    }

    @Test
    @Transactional
    void periodicReconcileSweepsIncompleteCloses() throws Exception {
        // A CLOSED row left incomplete by a WS-close that fell back to status-only
        // during a Bybit outage (no entry/exit/pnl/reason). The PERIODIC reconcile
        // path — not just the admin backfill endpoint — must self-heal it once
        // Bybit is reachable again. Before the fix, reconcileAccount only touched
        // OPEN rows, so this row would stay incomplete forever.
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.CLOSED);
        t.setStopPrice(new BigDecimal("49500"));
        t.setTargetPrice(new BigDecimal("52000"));
        t.setQty(new BigDecimal("0.001"));
        t.setExchangeOrderLinkId("ex-test-sweep");
        tradeRepo.persist(t);

        stubPositionsEmpty();                               // OPEN-row loop finds nothing to do
        stubClosedPnlFullPayload("50000", "49500", "-5.0", "Sell");

        reconciler.reconcileAccount(account);               // periodic path, NOT backfillIncompleteCloses

        ExecutedTrade refreshed = tradeRepo.findById(t.getId());
        assertNotNull(refreshed.getEntryPrice());
        assertEquals(0, new BigDecimal("50000").compareTo(refreshed.getEntryPrice()));
        assertNotNull(refreshed.getExitPrice());
        assertNotNull(refreshed.getRealizedPnlUsdt());
        assertEquals(com.cryptoradar.execution.model.ExitReason.INITIAL_STOP, refreshed.getExitReason());
    }

    private Map<String, Object> closedPnlEntry(String orderId, String avgEntry, String avgExit,
                                               String pnl, long createdMs) {
        return Map.ofEntries(
                Map.entry("symbol", "BTCUSDT"),
                Map.entry("orderId", orderId),
                Map.entry("side", "Buy"),   // closing a SHORT is a Buy
                Map.entry("qty", "0.01"),
                Map.entry("orderPrice", avgExit),
                Map.entry("avgEntryPrice", avgEntry),
                Map.entry("avgExitPrice", avgExit),
                Map.entry("closedPnl", pnl),
                Map.entry("openFee", "0.1"),
                Map.entry("closeFee", "0.1"),
                Map.entry("createdTime", String.valueOf(createdMs)),
                Map.entry("updatedTime", String.valueOf(createdMs)));
    }

    private ExecutedTrade incompleteBtcShort(long closedMs, String linkId) {
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("SHORT");
        t.setStatus(TradeStatus.CLOSED);
        t.setStopPrice(new BigDecimal("66000"));   // SHORT: stop above entry
        t.setTargetPrice(new BigDecimal("60000"));
        t.setQty(new BigDecimal("0.01"));
        // Opened 1h before it closed — realistic, and keeps the close inside the
        // [openedAt-1h, closedAt+24h] match window.
        t.setOpenedAt(java.time.Instant.ofEpochMilli(closedMs - 3_600_000L));
        t.setClosedAt(java.time.Instant.ofEpochMilli(closedMs));
        t.setExchangeOrderLinkId(linkId);
        return t;
    }

    @Test
    @Transactional
    void sweepDoesNotAttributeSameClosedPnlToTwoTrades() throws Exception {
        // Reproduces the BTC 255/257 duplicate: two same-symbol SHORTs closed
        // days apart whose wide match windows both contain both closed-pnl
        // entries. First-in-window matching gave BOTH the same entry; the fix
        // must give each its own (closest-in-time + dedup of consumed entries).
        long now = System.currentTimeMillis();
        long closeA = now - 2 * 3_600_000L;    // earlier close
        long closeB = now - 30 * 60_000L;      // later close

        ExecutedTrade a = incompleteBtcShort(closeA, "ex-dup-a");
        ExecutedTrade b = incompleteBtcShort(closeB, "ex-dup-b");
        tradeRepo.persist(a);
        tradeRepo.persist(b);

        // CLOSE-A listed FIRST so the old first-in-window logic would hand it to
        // both trades. Each entry's createdTime sits next to one trade's close.
        stubFor(get(urlPathEqualTo("/v5/position/closed-pnl"))
                .willReturn(okJson(mapper.writeValueAsString(Map.of(
                        "retCode", 0, "retMsg", "OK",
                        "result", Map.of("list", List.of(
                                closedPnlEntry("CLOSE-A", "64000", "65000", "-22.0", closeA),
                                closedPnlEntry("CLOSE-B", "62000", "60000", "30.0", closeB))),
                        "time", 1700000100000L)))));

        int recovered = reconciler.backfillIncompleteCloses(account.getId(), 50);
        assertEquals(2, recovered);

        ExecutedTrade ra = tradeRepo.findById(a.getId());
        ExecutedTrade rb = tradeRepo.findById(b.getId());
        // The two trades must NOT share the same closed-pnl entry.
        assertNotEquals(0, ra.getExitPrice().compareTo(rb.getExitPrice()),
                "two trades must not be attributed the same closed-pnl entry");
        // A (closed ~closeA) → CLOSE-A; B (closed ~closeB) → CLOSE-B.
        assertEquals(0, new BigDecimal("65000").compareTo(ra.getExitPrice()));
        assertEquals(0, new BigDecimal("60000").compareTo(rb.getExitPrice()));
        assertEquals(0, new BigDecimal("-22.0").compareTo(ra.getRealizedPnlUsdt()));
        assertEquals(0, new BigDecimal("30.0").compareTo(rb.getRealizedPnlUsdt()));
    }

    @Test
    @Transactional
    void backfillIsIdempotent() throws Exception {
        // A row already fully populated should not be re-counted as recovered.
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.CLOSED);
        t.setEntryPrice(new BigDecimal("50000"));
        t.setExitPrice(new BigDecimal("51000"));
        t.setStopPrice(new BigDecimal("49500"));
        t.setTargetPrice(new BigDecimal("52000"));
        t.setQty(new BigDecimal("0.001"));
        t.setRealizedPnlUsdt(new BigDecimal("1.0"));
        t.setFeesUsdt(new BigDecimal("0.05"));
        t.setRealizedRMultiple(new BigDecimal("2.0000"));
        t.setExitReason(com.cryptoradar.execution.model.ExitReason.TARGET);
        t.setExchangeOrderLinkId("ex-test-idem");
        tradeRepo.persist(t);

        int recovered = reconciler.backfillIncompleteCloses(account.getId(), 50);
        // No incomplete rows to scan → no Bybit call → 0 recovered.
        assertEquals(0, recovered);
    }

    @Test
    @Transactional
    void closeFromReconcileMislabeledLossesNoLongerDefaultToTarget() throws Exception {
        // Reproduces the Phase-2 bug: externally-closed LONG with negative PnL.
        // Old code defaulted exit_reason to TARGET; new code must classify as
        // INITIAL_STOP based on price + pnl sign.
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.OPEN);
        t.setEntryPrice(new BigDecimal("50000"));
        t.setStopPrice(new BigDecimal("49500"));
        t.setTargetPrice(new BigDecimal("52000"));
        t.setQty(new BigDecimal("0.001"));
        t.setExchangeOrderLinkId("ex-test-loss");
        tradeRepo.persist(t);

        stubPositionsEmpty();
        // Exit at 49500 (stop price), negative pnl — the realistic shape for
        // a losing trade closed by Bybit's stop.
        stubClosedPnlFullPayload("50000", "49500", "-5.0", "Sell");

        reconciler.reconcileAccount(account);

        ExecutedTrade refreshed = tradeRepo.findById(t.getId());
        assertEquals(TradeStatus.CLOSED, refreshed.getStatus());
        assertEquals(com.cryptoradar.execution.model.ExitReason.INITIAL_STOP, refreshed.getExitReason());
    }

    @Test
    @Transactional
    void applyCloseFromWsStyleFillPopulatesExitPnlAndR() {
        // Mirrors the WS close-capture path: a SHORT trade closed by a Buy fill.
        // The synthesized ClosedPnlV5 carries avgExitPrice + closedPnl + closeFee
        // but NO avgEntryPrice (the trade already holds entry) and NO openFee
        // (charged on the entry fill). applyClose must populate exit/pnl/fees/R
        // without depending on the closed-pnl REST endpoint.
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(account.getId());
        t.setSymbol("BTCUSDT");
        t.setDirection("SHORT");
        t.setStatus(TradeStatus.OPEN);
        t.setEntryPrice(new BigDecimal("50000"));
        t.setStopPrice(new BigDecimal("50500"));   // SHORT: stop above entry, risk 500
        t.setQty(new BigDecimal("0.001"));
        t.setExchangeOrderLinkId("ex-ws-close");
        tradeRepo.persist(t);

        // exit 49000 → SHORT profit (50000-49000)/500 = 2.0R
        ClosedPnlV5 wsClose = new ClosedPnlV5(
                "BTCUSDT", "oid-ws", "Buy", "0.001",
                "49000", null, "49000", "1.0", null, "0.05", "1700000100000", "1700000100000");

        boolean filled = reconciler.applyClose(account, t, wsClose);

        ExecutedTrade refreshed = tradeRepo.findById(t.getId());
        assertTrue(filled);
        assertEquals(TradeStatus.CLOSED, refreshed.getStatus());
        assertEquals(0, new BigDecimal("49000").compareTo(refreshed.getExitPrice()));
        assertEquals(0, new BigDecimal("1.0").compareTo(refreshed.getRealizedPnlUsdt()));
        assertEquals(0, new BigDecimal("0.05").compareTo(refreshed.getFeesUsdt()));
        assertNotNull(refreshed.getRealizedRMultiple());
        assertEquals(0, new BigDecimal("2.0000").compareTo(refreshed.getRealizedRMultiple()));
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
