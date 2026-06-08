package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vector E regression guards. Phase 2 data: 19 of 60 INITIAL_STOP losers
 * reached &lt; 0.2% MFE — dead-on-entry signals that drifted sideways for
 * hours before finally hitting the full -1R stop. This test pins the
 * age + MFE + MAE triple so a future tuning pass produces predictable
 * behaviour.
 */
class OutcomeEvaluatorStagnationTest {

    private OutcomeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new OutcomeEvaluator(null, null, null);
        evaluator.stagnationExitEnabled = true;
        evaluator.stagnationMinAgeMinutes = 45;
        evaluator.stagnationMfeThresholdPct = 0.2;
        evaluator.stagnationMaeFloorPct = -0.3;
        evaluator.stagnationAtrLookbackBars = 45;
        evaluator.stagnationMfeAtrMultiplier = 0.25;
        evaluator.stagnationMaeAtrMultiplier = 0.4;
    }

    @Test
    @DisplayName("fresh outcome (< min age) is not exited even if stagnant")
    void freshOutcomeNotExited() {
        SignalOutcome out = stagnantLongOutcome(Instant.now().minus(30, ChronoUnit.MINUTES));
        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(out.getFiredAt().plusSeconds(1800), 100.0), -1.0);
        assertFalse(closed);
        assertNull(out.getClosedAt());
    }

    @Test
    @DisplayName("aged outcome with MFE and MAE in band exits STAGNATION")
    void agedStagnantOutcomeExits() {
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        CandleBar lastBar = barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.05);

        boolean closed = evaluator.stagnationExitIfEligible(out, lastBar, -1.0);

        assertTrue(closed);
        assertEquals(OutcomeStatus.HIT_STOP, out.getStatus());
        assertEquals("STAGNATION", out.getFinalExitReason());
        assertEquals(100.05, out.getClosedPrice(), 1e-9);
        assertNotNull(out.getClosedAt());
    }

    @Test
    @DisplayName("MFE ≥ threshold means trade got moving — not stagnant")
    void positionThatMovedFavorablyIsNotStagnant() {
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        out.setMaxFavorablePct(0.5);  // above the 0.2 threshold

        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.5), -1.0);
        assertFalse(closed);
    }

    @Test
    @DisplayName("MAE past floor means trade is losing hard — let the stop handle it")
    void positionLosingHardIsNotStagnant() {
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        out.setMaxAdversePct(-0.8);   // past the -0.3 floor

        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 99.5), -1.0);
        assertFalse(closed);
    }

    @Test
    @DisplayName("kill-switch disables the gate")
    void disabledGateNeverExits() {
        evaluator.stagnationExitEnabled = false;
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);

        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.05), -1.0);
        assertFalse(closed);
    }

    @Test
    @DisplayName("STAGNATION exit carries realistic loss — not a full -1R")
    void stagnationLossIsSmallerThanFullStop() {
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        // Bar close below entry by 0.1% — inside the MAE floor
        CandleBar lastBar = barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 99.9);

        boolean closed = evaluator.stagnationExitIfEligible(out, lastBar, -1.0);

        assertTrue(closed);
        // Risk = 1.0 (entry 100 - stop 99). Close at 99.9 → gross R = -0.1
        // Fees per the default 10bps on 1% risk = 0.1R → net R = -0.2
        assertTrue(out.getRealizedRMultiple() > -0.5,
                "stagnation exit at 99.9 should net better than -0.5R; got " + out.getRealizedRMultiple());
        assertTrue(out.getRealizedRMultiple() < 0.0,
                "still a loss though; got " + out.getRealizedRMultiple());
    }

    @Test
    @DisplayName("ATR-scaled: low-vol symbol (ATR 0.05%) does NOT stagnate when MFE=0.1% — MFE exceeds 0.25*ATR")
    void atrScaledLowVolSymbolNotStagnant() {
        // Mirrors TRX-LONG-TC root cause: TRX 45m ATR ~0.05%, the trade's
        // 0.1% MFE looks "stagnant" by absolute 0.2% threshold but is
        // actually 2x the symbol's noise floor — not stagnant.
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        double atrPct = 0.05;  // very low vol

        boolean closed = evaluator.stagnationExitIfEligible(
                out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.05), atrPct);
        assertFalse(closed, "low-vol symbol with MFE=0.1% > 0.25*0.05% = 0.0125% should not stagnate");
    }

    @Test
    @DisplayName("ATR-scaled: high-vol symbol (ATR 1.0%) DOES stagnate when MFE=0.1% — MFE below 0.25*ATR")
    void atrScaledHighVolSymbolStagnant() {
        // Symmetric case: BTC ATR ~0.3-1.0% in 45m. MFE=0.1% really is dead
        // for BTC. Absolute 0.2% threshold would have missed this trade.
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        double atrPct = 1.0;  // high vol

        boolean closed = evaluator.stagnationExitIfEligible(
                out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.05), atrPct);
        assertTrue(closed, "high-vol symbol with MFE=0.1% < 0.25*1.0% = 0.25% should stagnate");
        assertEquals("STAGNATION", out.getFinalExitReason());
    }

    @Test
    @DisplayName("atrPctOver returns -1 when bars below lookback+1 — caller falls back to absolutes")
    void atrPctOverInsufficientBarsReturnsMinusOne() {
        java.util.List<CandleBar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bars.add(new CandleBar(Instant.now().plusSeconds(i * 60),
                    100.0, 100.5, 99.5, 100.0, 1000));
        }
        double atrPct = evaluator.atrPctOver(bars, 45, 100.0);
        assertEquals(-1.0, atrPct, 1e-9);
    }

    @Test
    @DisplayName("atrPctOver computes ATR from TR=high-low when no gap, divides by entry, returns percent")
    void atrPctOverComputesFromTrueRange() {
        // 50 bars, each high=101, low=99, close=100 → TR every bar = 2
        // ATR(45) = 2, entry 100 → ATR pct = 2.0
        java.util.List<CandleBar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            bars.add(new CandleBar(Instant.now().plusSeconds(i * 60),
                    100.0, 101.0, 99.0, 100.0, 1000));
        }
        double atrPct = evaluator.atrPctOver(bars, 45, 100.0);
        assertEquals(2.0, atrPct, 1e-9);
    }

    private SignalOutcome stagnantLongOutcome(Instant firedAt) {
        SignalOutcome out = new SignalOutcome();
        out.setDirection("LONG");
        out.setEntryPrice(100.0);
        out.setStopPrice(99.0);     // 1% risk
        out.setTargetPrice(105.0);
        out.setSymbol("TESTUSDT");
        out.setStrategy("unit-test");
        out.setSignalType("BUY");
        out.setRiskRewardRatio(5.0);
        out.setAlignment(60);
        out.setOverallScore(40.0);
        out.setFiredAt(firedAt);
        out.setSignalId("test-" + firedAt.toEpochMilli());
        // Stagnant: tiny MFE, shallow MAE
        out.setMaxFavorablePct(0.1);
        out.setMaxAdversePct(-0.1);
        return out;
    }

    private CandleBar barAtClose(Instant time, double close) {
        return new CandleBar(time, close, close, close, close, 0);
    }
}
