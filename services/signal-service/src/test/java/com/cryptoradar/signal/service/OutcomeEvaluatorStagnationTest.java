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
        evaluator = new OutcomeEvaluator(null, null);
        evaluator.stagnationExitEnabled = true;
        evaluator.stagnationMinAgeMinutes = 45;
        evaluator.stagnationMfeThresholdPct = 0.2;
        evaluator.stagnationMaeFloorPct = -0.3;
    }

    @Test
    @DisplayName("fresh outcome (< min age) is not exited even if stagnant")
    void freshOutcomeNotExited() {
        SignalOutcome out = stagnantLongOutcome(Instant.now().minus(30, ChronoUnit.MINUTES));
        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(out.getFiredAt().plusSeconds(1800), 100.0));
        assertFalse(closed);
        assertNull(out.getClosedAt());
    }

    @Test
    @DisplayName("aged outcome with MFE and MAE in band exits STAGNATION")
    void agedStagnantOutcomeExits() {
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        CandleBar lastBar = barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.05);

        boolean closed = evaluator.stagnationExitIfEligible(out, lastBar);

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

        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.5));
        assertFalse(closed);
    }

    @Test
    @DisplayName("MAE past floor means trade is losing hard — let the stop handle it")
    void positionLosingHardIsNotStagnant() {
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        out.setMaxAdversePct(-0.8);   // past the -0.3 floor

        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 99.5));
        assertFalse(closed);
    }

    @Test
    @DisplayName("kill-switch disables the gate")
    void disabledGateNeverExits() {
        evaluator.stagnationExitEnabled = false;
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);

        boolean closed = evaluator.stagnationExitIfEligible(out, barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 100.05));
        assertFalse(closed);
    }

    @Test
    @DisplayName("STAGNATION exit carries realistic loss — not a full -1R")
    void stagnationLossIsSmallerThanFullStop() {
        Instant firedAt = Instant.now().minus(60, ChronoUnit.MINUTES);
        SignalOutcome out = stagnantLongOutcome(firedAt);
        // Bar close below entry by 0.1% — inside the MAE floor
        CandleBar lastBar = barAtClose(firedAt.plus(55, ChronoUnit.MINUTES), 99.9);

        boolean closed = evaluator.stagnationExitIfEligible(out, lastBar);

        assertTrue(closed);
        // Risk = 1.0 (entry 100 - stop 99). Close at 99.9 → gross R = -0.1
        // Fees per the default 10bps on 1% risk = 0.1R → net R = -0.2
        assertTrue(out.getRealizedRMultiple() > -0.5,
                "stagnation exit at 99.9 should net better than -0.5R; got " + out.getRealizedRMultiple());
        assertTrue(out.getRealizedRMultiple() < 0.0,
                "still a loss though; got " + out.getRealizedRMultiple());
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
