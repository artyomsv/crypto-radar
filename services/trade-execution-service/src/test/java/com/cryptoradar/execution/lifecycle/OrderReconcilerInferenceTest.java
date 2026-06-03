package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.client.bybit.dto.ClosedPnlV5;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.ExitReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-unit tests for {@link OrderReconciler#inferExitReason}. The classifier
 * decides between TARGET / INITIAL_STOP / TRAIL_STOP / MANUAL based on the
 * exit price relative to the trade's stop and target levels — and falls back
 * to PnL sign + trail-active flag when those levels are missing.
 *
 * <p>Replaces the silent default-to-TARGET behavior that mislabeled 26 of 28
 * externally-closed Phase-2 trades. Each test pins one branch of that
 * decision tree.
 */
class OrderReconcilerInferenceTest {

    private static ExecutedTrade longTrade(BigDecimal entry, BigDecimal stop, BigDecimal target) {
        ExecutedTrade t = new ExecutedTrade();
        t.setDirection("LONG");
        t.setEntryPrice(entry);
        t.setStopPrice(stop);
        t.setTargetPrice(target);
        return t;
    }

    private static ExecutedTrade shortTrade(BigDecimal entry, BigDecimal stop, BigDecimal target) {
        ExecutedTrade t = new ExecutedTrade();
        t.setDirection("SHORT");
        t.setEntryPrice(entry);
        t.setStopPrice(stop);
        t.setTargetPrice(target);
        return t;
    }

    private static ClosedPnlV5 close(String exit, String pnl) {
        return new ClosedPnlV5("BTCUSDT", "ord", "Sell", "1", exit, exit, exit, pnl,
                "0.5", "0.5", "1700000000000", "1700000000000");
    }

    private static ClosedPnlV5 closeNoLevels(String pnl) {
        return new ClosedPnlV5("BTCUSDT", "ord", "Sell", "1", null, null, null, pnl,
                "0.5", "0.5", "1700000000000", "1700000000000");
    }

    @Test
    void longTargetHitClassifiedAsTarget() {
        ExecutedTrade t = longTrade(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"));
        assertEquals(ExitReason.TARGET, OrderReconciler.inferExitReason(t, close("110.00", "10.0")));
    }

    @Test
    void longStopHitWithoutTrailIsInitialStop() {
        ExecutedTrade t = longTrade(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"));
        assertEquals(ExitReason.INITIAL_STOP, OrderReconciler.inferExitReason(t, close("95.00", "-5.0")));
    }

    @Test
    void longStopHitWithTrailActiveIsTrailStop() {
        ExecutedTrade t = longTrade(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"));
        t.setTrailTriggeredAt(Instant.now());
        assertEquals(ExitReason.TRAIL_STOP, OrderReconciler.inferExitReason(t, close("95.00", "-5.0")));
    }

    @Test
    void shortTargetHitClassifiedAsTarget() {
        ExecutedTrade t = shortTrade(new BigDecimal("100"), new BigDecimal("105"), new BigDecimal("90"));
        assertEquals(ExitReason.TARGET, OrderReconciler.inferExitReason(t, close("90.00", "10.0")));
    }

    @Test
    void shortStopHitWithoutTrailIsInitialStop() {
        ExecutedTrade t = shortTrade(new BigDecimal("100"), new BigDecimal("105"), new BigDecimal("90"));
        assertEquals(ExitReason.INITIAL_STOP, OrderReconciler.inferExitReason(t, close("105.00", "-5.0")));
    }

    @Test
    void exitBetweenStopAndTargetWithTrailIsTrailStop() {
        // LONG, exit 102 (between stop 95 and target 110), trail active → trail.
        ExecutedTrade t = longTrade(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"));
        t.setTrailTriggeredAt(Instant.now());
        assertEquals(ExitReason.TRAIL_STOP, OrderReconciler.inferExitReason(t, close("102.00", "2.0")));
    }

    @Test
    void exitBetweenLevelsWithProfitAndNoTrailFallsBackToTarget() {
        // Slippage on a target hit can land the fill back inside the band.
        // PnL > 0 + no trail → TARGET (winning trade can't be a stop hit).
        ExecutedTrade t = longTrade(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"));
        assertEquals(ExitReason.TARGET, OrderReconciler.inferExitReason(t, close("102.00", "2.0")));
    }

    @Test
    void exitBetweenLevelsWithLossAndNoTrailFallsBackToInitialStop() {
        // Volatile stop-hit can fill above the stop level (price gaps below
        // the stop, market order fills on the rebound). PnL < 0 + no trail →
        // INITIAL_STOP, not MANUAL — losing LONG with no trail can only have
        // been closed by the stop.
        ExecutedTrade t = longTrade(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"));
        assertEquals(ExitReason.INITIAL_STOP, OrderReconciler.inferExitReason(t, close("96.50", "-3.5")));
    }

    @Test
    void slippageWithinTolerancePinsTarget() {
        // Exit 109.95 on target 110 — 0.045% short of target, inside the 0.1% tolerance band.
        ExecutedTrade t = longTrade(new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"));
        assertEquals(ExitReason.TARGET, OrderReconciler.inferExitReason(t, close("109.95", "9.95")));
    }

    @Test
    void fallbackToPnlSignWhenLevelsMissing() {
        // No stop/target on the trade row, no avg/order price on the close.
        ExecutedTrade t = new ExecutedTrade();
        t.setDirection("LONG");
        // Negative pnl, no trail → INITIAL_STOP.
        assertEquals(ExitReason.INITIAL_STOP, OrderReconciler.inferExitReason(t, closeNoLevels("-5.0")));
        // Positive pnl, no trail → TARGET.
        assertEquals(ExitReason.TARGET, OrderReconciler.inferExitReason(t, closeNoLevels("5.0")));
    }

    @Test
    void fallbackToPnlSignWithTrailActive() {
        ExecutedTrade t = new ExecutedTrade();
        t.setDirection("LONG");
        t.setTrailTriggeredAt(Instant.now());
        // Trail active dominates regardless of pnl sign.
        assertEquals(ExitReason.TRAIL_STOP, OrderReconciler.inferExitReason(t, closeNoLevels("-2.0")));
        assertEquals(ExitReason.TRAIL_STOP, OrderReconciler.inferExitReason(t, closeNoLevels("2.0")));
    }

    @Test
    void allMissingFallsBackToManual() {
        ExecutedTrade t = new ExecutedTrade();
        t.setDirection("LONG");
        // No levels, no pnl — we have nothing to infer from.
        assertEquals(ExitReason.MANUAL, OrderReconciler.inferExitReason(t, closeNoLevels(null)));
    }
}
