package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.SignalOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PR5 coverage: the evaluator records when MFE/MAE extended their extreme and
 * the fee-in-R helper computes the correct deduction. Both are prerequisites
 * for the per-strategy trail calibration and honest P&L reporting that this
 * PR introduces.
 */
class OutcomeEvaluatorTimingAndFeesTest {

    private final OutcomeEvaluator evaluator = new OutcomeEvaluator(null, null);

    // --- Timing ---

    @Test
    @DisplayName("MFE timing records seconds from fire to extending bar")
    void mfeTimingPopulated() {
        Instant fired = Instant.parse("2026-04-01T00:00:00Z");
        SignalOutcome out = newLongOutcome(fired);
        CandleBar advanceBar = new CandleBar(
                fired.plusSeconds(600), 100.0, 101.0, 99.9, 100.8);   // MFE = 1.0R

        evaluator.updateExcursions(out, advanceBar);

        assertEquals(1.0, out.getMaxFavorablePct(), 1e-9);
        assertNotNull(out.getTimeToMfeSeconds());
        assertEquals(600, out.getTimeToMfeSeconds());
    }

    @Test
    @DisplayName("MAE timing records seconds to worst point")
    void maeTimingPopulated() {
        Instant fired = Instant.parse("2026-04-01T00:00:00Z");
        SignalOutcome out = newLongOutcome(fired);
        CandleBar adverseBar = new CandleBar(
                fired.plusSeconds(300), 100.0, 100.1, 99.5, 99.6);    // MAE = -0.5%

        evaluator.updateExcursions(out, adverseBar);

        assertEquals(-0.5, out.getMaxAdversePct(), 1e-9);
        assertEquals(300, out.getTimeToMaeSeconds());
    }

    @Test
    @DisplayName("timing fields stay null when excursion does not extend")
    void timingNotUpdatedWhenNoExtremeChange() {
        Instant fired = Instant.parse("2026-04-01T00:00:00Z");
        SignalOutcome out = newLongOutcome(fired);
        out.setMaxFavorablePct(2.0);
        out.setMaxAdversePct(-1.0);

        // Bar whose extremes don't exceed what we already recorded.
        CandleBar quietBar = new CandleBar(
                fired.plusSeconds(900), 100.0, 100.5, 99.8, 100.3);

        evaluator.updateExcursions(out, quietBar);

        assertNull(out.getTimeToMfeSeconds(),
                "MFE time should not update when the bar doesn't print a new extreme");
        assertNull(out.getTimeToMaeSeconds(),
                "MAE time should not update when the bar doesn't print a new extreme");
    }

    // --- Fees ---

    @Test
    @DisplayName("fees in R: 10 bps on 1% risk = 0.1R")
    void feesBasicCase() {
        SignalOutcome out = newLongOutcome(Instant.now());
        out.setFeesBpsRoundTrip(10);
        // risk = 1.0 in price units on entry 100 = 1% riskPct
        double fees = evaluator.feesInRUnits(out, 1.0);
        assertEquals(0.1, fees, 1e-9);
    }

    @Test
    @DisplayName("fees in R: 20 bps on 2% risk = 0.1R")
    void feesScaleWithRisk() {
        SignalOutcome out = newLongOutcome(Instant.now());
        out.setFeesBpsRoundTrip(20);
        // risk = 2.0 → riskPct 2% → feesInR = 0.2%/2% = 0.1
        double fees = evaluator.feesInRUnits(out, 2.0);
        assertEquals(0.1, fees, 1e-9);
    }

    @Test
    @DisplayName("fees in R: zero risk returns zero fees (safety)")
    void feesZeroRiskGuard() {
        SignalOutcome out = newLongOutcome(Instant.now());
        out.setFeesBpsRoundTrip(10);
        assertEquals(0.0, evaluator.feesInRUnits(out, 0.0), 1e-9);
    }

    @Test
    @DisplayName("fees in R: null or zero fee config returns zero")
    void feesMissingConfigReturnsZero() {
        SignalOutcome out = newLongOutcome(Instant.now());
        out.setFeesBpsRoundTrip(0);
        assertEquals(0.0, evaluator.feesInRUnits(out, 1.0), 1e-9);

        out.setFeesBpsRoundTrip(null);
        assertEquals(0.0, evaluator.feesInRUnits(out, 1.0), 1e-9);
    }

    // --- Fixtures ---

    private SignalOutcome newLongOutcome(Instant firedAt) {
        SignalOutcome out = new SignalOutcome();
        out.setDirection("LONG");
        out.setEntryPrice(100.0);
        out.setStopPrice(99.0);
        out.setTargetPrice(105.0);
        out.setSymbol("TESTUSDT");
        out.setStrategy("unit-test");
        out.setSignalType("BUY");
        out.setRiskRewardRatio(5.0);
        out.setConfidence(60);
        out.setOverallScore(40.0);
        out.setFiredAt(firedAt);
        out.setSignalId("fee-test");
        return out;
    }
}
