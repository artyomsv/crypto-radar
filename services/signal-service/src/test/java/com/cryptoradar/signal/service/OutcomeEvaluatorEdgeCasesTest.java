package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Closes out the long-standing OutcomeEvaluator edge-case coverage gap
 * (techdebt 2-2-missing-tests-outcome-tracker-and-evaluator). The trail,
 * stagnation, and timing/fees paths are covered by dedicated test classes
 * — this one targets the smaller branches:
 *
 * <ul>
 *   <li>both stop+target inside one bar with trail INACTIVE → STOP wins</li>
 *   <li>both stop+target with trail ACTIVE → TARGET wins</li>
 *   <li>SHORT MFE/MAE sign conventions (easy to flip during edits)</li>
 *   <li>{@code atrPctOver} handles empty / undersized inputs</li>
 * </ul>
 */
class OutcomeEvaluatorEdgeCasesTest {

    private final OutcomeEvaluator evaluator = new OutcomeEvaluator(null, null, null);

    @Test
    @DisplayName("LONG: stop and target both touched in one bar with trail INACTIVE → STOP wins")
    void bothLevelsSameBarTrailInactivePicksStop() {
        SignalOutcome out = newLongOutcome();
        // dynamicStopPrice = null → trail inactive → pessimistic policy
        CandleBar bar = new CandleBar(Instant.now(), 100.0, 106.0, 98.0, 100.0, 1000);

        OutcomeStatus hit = evaluator.detectHit(out, bar);

        assertEquals(OutcomeStatus.HIT_STOP, hit);
    }

    @Test
    @DisplayName("LONG: stop and target both touched in one bar with trail ACTIVE → TARGET wins")
    void bothLevelsSameBarTrailActivePicksTarget() {
        SignalOutcome out = newLongOutcome();
        out.setDynamicStopPrice(100.5);  // trail-locked above entry
        CandleBar bar = new CandleBar(Instant.now(), 100.6, 106.0, 100.0, 100.6, 1000);

        OutcomeStatus hit = evaluator.detectHit(out, bar);

        assertEquals(OutcomeStatus.HIT_TARGET, hit);
    }

    @Test
    @DisplayName("SHORT: MFE/MAE signs flipped — price drops below entry are favorable")
    void shortMfeIsPositiveWhenPriceDrops() {
        SignalOutcome out = newShortOutcome();
        // bar high=99.5 (favorable for SHORT), bar low=98.5 (most favorable)
        // expected MFE = (entry - bar.low) / entry × 100 = (100 - 98.5) / 100 = 1.5%
        // expected MAE = (entry - bar.high) / entry × 100 = (100 - 99.5) / 100 = 0.5%
        // For SHORT, MAE should be negative when bar high < entry (no adverse move).
        // Actually: pctMove(entry, worstPrice, false) where worstPrice = bar.high
        //  = -(99.5 - 100)/100 × 100 = 0.5 → which is favorable (positive)
        // The convention: for SHORT, "worstPrice" is bar.high — when high < entry,
        // there is no adverse excursion, so MAE stays ≥ 0.
        // Use a bar that DOES go adverse to verify the sign.
        CandleBar bar = new CandleBar(Instant.now(), 100.0, 100.4, 98.5, 99.0, 1000);

        evaluator.updateExcursions(out, bar);

        // MFE: best for SHORT is the LOW. (entry - low) / entry × 100 = 1.5%
        assertEquals(1.5, out.getMaxFavorablePct(), 1e-9);
        // MAE: worst for SHORT is the HIGH. -(high - entry) / entry × 100 = -0.4%
        assertEquals(-0.4, out.getMaxAdversePct(), 1e-9);
    }

    @Test
    @DisplayName("LONG: stop hit but target not → HIT_STOP regardless of trail state")
    void onlyStopTouchedIsStopHit() {
        SignalOutcome out = newLongOutcome();
        CandleBar bar = new CandleBar(Instant.now(), 99.5, 99.6, 98.8, 99.0, 1000);
        assertEquals(OutcomeStatus.HIT_STOP, evaluator.detectHit(out, bar));
    }

    @Test
    @DisplayName("LONG: target hit but stop not → HIT_TARGET")
    void onlyTargetTouchedIsTargetHit() {
        SignalOutcome out = newLongOutcome();
        CandleBar bar = new CandleBar(Instant.now(), 100.5, 105.5, 100.2, 105.0, 1000);
        assertEquals(OutcomeStatus.HIT_TARGET, evaluator.detectHit(out, bar));
    }

    @Test
    @DisplayName("LONG: bar with no level touched returns null hit")
    void noLevelTouchedReturnsNull() {
        SignalOutcome out = newLongOutcome();
        CandleBar bar = new CandleBar(Instant.now(), 100.5, 101.0, 100.0, 100.8, 1000);
        assertNull(evaluator.detectHit(out, bar));
    }

    @Test
    @DisplayName("atrPctOver returns -1 on empty bar list")
    void atrPctOverEmptyReturnsMinusOne() {
        assertEquals(-1.0, evaluator.atrPctOver(List.of(), 45, 100.0));
    }

    @Test
    @DisplayName("atrPctOver returns -1 when entryPrice is zero")
    void atrPctOverZeroEntryReturnsMinusOne() {
        List<CandleBar> bars = uniformBars(100, 100.0);
        assertEquals(-1.0, evaluator.atrPctOver(bars, 45, 0.0));
    }

    @Test
    @DisplayName("feesInRUnits returns 0 when risk is 0 (no divide-by-zero)")
    void feesInRUnitsHandlesZeroRisk() {
        SignalOutcome out = newLongOutcome();
        out.setFeesBpsRoundTrip(10);
        // risk argument = 0 → guard short-circuits before divide
        assertEquals(0.0, evaluator.feesInRUnits(out, 0.0));
    }

    @Test
    @DisplayName("feesInRUnits returns 0 when fees not configured")
    void feesInRUnitsHandlesNullFees() {
        SignalOutcome out = newLongOutcome();
        out.setFeesBpsRoundTrip(null);
        assertEquals(0.0, evaluator.feesInRUnits(out, 1.0));
    }

    @Test
    @DisplayName("feesInRUnits computes fees-as-R correctly: 10bps fee on 1% risk = 0.1R")
    void feesInRUnitsComputesCorrectly() {
        SignalOutcome out = newLongOutcome();
        out.setEntryPrice(100.0);
        out.setFeesBpsRoundTrip(10);
        // 10bps = 0.001; risk_pct = 1.0/100 = 0.01 → 0.001/0.01 = 0.1R
        assertEquals(0.1, evaluator.feesInRUnits(out, 1.0), 1e-9);
    }

    @Test
    @DisplayName("SHORT: stop hit (bar high crosses stop) → HIT_STOP")
    void shortStopHitWhenHighCrosses() {
        SignalOutcome out = newShortOutcome();
        // entry=100, stop=101 → high≥101 triggers stop
        CandleBar bar = new CandleBar(Instant.now(), 99.5, 101.2, 99.0, 100.0, 1000);
        assertEquals(OutcomeStatus.HIT_STOP, evaluator.detectHit(out, bar));
    }

    @Test
    @DisplayName("SHORT: target hit (bar low crosses target) → HIT_TARGET")
    void shortTargetHitWhenLowCrosses() {
        SignalOutcome out = newShortOutcome();
        // entry=100, target=95 → low≤95 triggers target
        CandleBar bar = new CandleBar(Instant.now(), 96.5, 96.8, 94.9, 95.0, 1000);
        assertEquals(OutcomeStatus.HIT_TARGET, evaluator.detectHit(out, bar));
    }

    private SignalOutcome newLongOutcome() {
        SignalOutcome out = new SignalOutcome();
        out.setDirection("LONG");
        out.setEntryPrice(100.0);
        out.setStopPrice(99.0);
        out.setTargetPrice(105.0);
        out.setSymbol("TESTUSDT");
        out.setStrategy("unit");
        out.setSignalType("BUY");
        out.setFiredAt(Instant.now());
        out.setSignalId("test");
        return out;
    }

    private SignalOutcome newShortOutcome() {
        SignalOutcome out = new SignalOutcome();
        out.setDirection("SHORT");
        out.setEntryPrice(100.0);
        out.setStopPrice(101.0);
        out.setTargetPrice(95.0);
        out.setSymbol("TESTUSDT");
        out.setStrategy("unit");
        out.setSignalType("SELL");
        out.setFiredAt(Instant.now());
        out.setSignalId("test");
        return out;
    }

    private List<CandleBar> uniformBars(int count, double close) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new CandleBar(
                        Instant.now().plusSeconds(i * 60),
                        close, close + 0.5, close - 0.5, close, 1000))
                .toList();
    }
}
