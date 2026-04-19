package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for PR2 trailing-stop logic. Exercises the rung ratchet, the
 * effective-stop resolution, and the exit-reason classification directly on
 * an {@link OutcomeEvaluator} with nulled-out dependencies — the trail math
 * doesn't touch the repository or candle client so the stubbing cost would
 * outweigh the coverage gain.
 */
class OutcomeEvaluatorTrailingTest {

    private static final double ENTRY = 100.0;
    private static final double INITIAL_STOP_LONG = 99.0;   // risk = 1.0 in price units
    private static final double TARGET_LONG = 105.0;        // RR = 5

    private final OutcomeEvaluator evaluator = new OutcomeEvaluator(null, null);

    // --- LONG ladder progression ---

    @Test
    @DisplayName("trail stays inactive while MFE < activation (1R)")
    void trailInactiveBelowActivation() {
        SignalOutcome out = newLongOutcome();
        out.setMaxFavorablePct(0.4);   // 0.4R at riskPct 1%
        evaluator.updateTrailingStop(out, anyBar());

        assertNull(out.getDynamicStopPrice());
        assertNull(out.getTrailTriggeredAt());
        assertEquals(0.0, out.getTrailHighestR());
    }

    @Test
    @DisplayName("first trail rung activates at MFE = 1R → stop locks at +0.5R")
    void trailActivatesAtFirstRung() {
        SignalOutcome out = newLongOutcome();
        out.setMaxFavorablePct(1.0);
        evaluator.updateTrailingStop(out, anyBar());

        assertNotNull(out.getDynamicStopPrice());
        assertEquals(100.5, out.getDynamicStopPrice(), 1e-9,
                "stop should ratchet to entry + 0.5R on first rung");
        assertNotNull(out.getTrailTriggeredAt());
        assertEquals(0.5, out.getTrailHighestR(), 1e-9);
    }

    @Test
    @DisplayName("rung ladder at 0.5R step: MFE 2R → stop +1.5R")
    void trailRungLadderProgresses() {
        SignalOutcome out = newLongOutcome();
        out.setMaxFavorablePct(2.0);
        evaluator.updateTrailingStop(out, anyBar());

        assertEquals(101.5, out.getDynamicStopPrice(), 1e-9,
                "MFE 2R → rung 2 → stop at entry + (1.0 + 1.0 - 0.5)*risk = +1.5R");
        assertEquals(1.5, out.getTrailHighestR(), 1e-9);
    }

    @Test
    @DisplayName("trail is monotonic — does not loosen when cumulative MFE stays above peak")
    void trailDoesNotLoosen() {
        SignalOutcome out = newLongOutcome();
        out.setMaxFavorablePct(3.0);   // MFE 3R
        evaluator.updateTrailingStop(out, anyBar());
        double lockedStop = out.getDynamicStopPrice();

        // subsequent bar: MFE is cumulative — it never falls. But if for any
        // reason trail re-runs on the same cumulative state, highest rung stays put.
        evaluator.updateTrailingStop(out, anyBar());
        assertEquals(lockedStop, out.getDynamicStopPrice(), 1e-9,
                "trail must never retreat once ratcheted");
        assertEquals(2.5, out.getTrailHighestR(), 1e-9);
    }

    @Test
    @DisplayName("backfill case: existing pending row with cumulative MFE > 1R activates immediately")
    void trailActivatesFromCumulativeMfe() {
        SignalOutcome out = newLongOutcome();
        // Simulates a row that lived through many bars pre-deploy; MFE peak
        // was reached yesterday, price has retraced but trail should still fire.
        out.setMaxFavorablePct(2.8);
        evaluator.updateTrailingStop(out, anyBar());

        assertNotNull(out.getDynamicStopPrice(),
                "cumulative MFE ≥ activation should ratchet trail on first evaluator pass");
        assertEquals(2.0, out.getTrailHighestR(), 1e-9,
                "2.8R MFE → rung 3 → stop at 2.0R");
    }

    // --- Hit detection semantics ---

    @Test
    @DisplayName("initial stop hit before trail activates → HIT_STOP (pessimistic)")
    void initialStopHitBeforeTrail() {
        SignalOutcome out = newLongOutcome();
        // Both target and initial stop inside the same bar; pessimistic rule wins.
        CandleBar spike = bar(99.5, 106.0, 98.0, 99.0);   // high past target, low past stop

        OutcomeStatus hit = evaluator.detectHit(out, spike);
        assertEquals(OutcomeStatus.HIT_STOP, hit,
                "trail inactive → pessimistic convention: stop first");
    }

    @Test
    @DisplayName("target hit after trail active → HIT_TARGET (optimistic)")
    void targetHitAfterTrailActive() {
        SignalOutcome out = newLongOutcome();
        out.setDynamicStopPrice(100.5);   // trail locked at +0.5R from prior bars
        out.setTrailHighestR(0.5);
        CandleBar spike = bar(101.5, 106.0, 100.4, 105.5);  // high past target, low under trail

        OutcomeStatus hit = evaluator.detectHit(out, spike);
        assertEquals(OutcomeStatus.HIT_TARGET, hit,
                "trail active → optimistic: target prints first on the way down");
    }

    @Test
    @DisplayName("bar low through trail → HIT_STOP at dynamic stop, not initial")
    void trailStopFillsAboveInitial() {
        SignalOutcome out = newLongOutcome();
        out.setDynamicStopPrice(100.5);
        out.setTrailHighestR(0.5);
        CandleBar retrace = bar(102.0, 102.5, 100.0, 100.2);

        OutcomeStatus hit = evaluator.detectHit(out, retrace);
        assertEquals(OutcomeStatus.HIT_STOP, hit);
    }

    // --- SHORT direction symmetry ---

    @Test
    @DisplayName("SHORT: trail activates on drop equal to 1R and locks -0.5R")
    void trailActivatesShort() {
        SignalOutcome out = newShortOutcome();
        // risk = 1 in price units (stop is 101, entry 100). MFE 1R = price drops to 99.
        // max_favorable_pct for a short is positive-when-favorable (pctMove negates).
        out.setMaxFavorablePct(1.0);
        evaluator.updateTrailingStop(out, anyBar());

        assertEquals(99.5, out.getDynamicStopPrice(), 1e-9,
                "SHORT: stop ratchets below entry by 0.5R on first rung");
        assertEquals(0.5, out.getTrailHighestR(), 1e-9);
    }

    @Test
    @DisplayName("SHORT: rung 3 (MFE 2.5R) → stop at entry -2R")
    void trailRungShort() {
        SignalOutcome out = newShortOutcome();
        out.setMaxFavorablePct(2.5);
        evaluator.updateTrailingStop(out, anyBar());

        assertEquals(98.0, out.getDynamicStopPrice(), 1e-9);
        assertEquals(2.0, out.getTrailHighestR(), 1e-9);
    }

    // --- Fixtures ---

    private SignalOutcome newLongOutcome() {
        SignalOutcome out = new SignalOutcome();
        out.setDirection("LONG");
        out.setEntryPrice(ENTRY);
        out.setStopPrice(INITIAL_STOP_LONG);
        out.setTargetPrice(TARGET_LONG);
        out.setSymbol("TESTUSDT");
        out.setStrategy("unit-test");
        out.setSignalType("BUY");
        out.setRiskRewardRatio(5.0);
        out.setConfidence(60);
        out.setOverallScore(40.0);
        out.setFiredAt(Instant.now().minusSeconds(3600));
        out.setSignalId("test-" + Instant.now().toEpochMilli());
        // trail config defaults: 1.0 / 0.5 / 0.5 populated by entity
        return out;
    }

    private SignalOutcome newShortOutcome() {
        SignalOutcome out = newLongOutcome();
        out.setDirection("SHORT");
        out.setStopPrice(101.0);    // above entry for short
        out.setTargetPrice(95.0);   // below entry
        out.setSignalType("SELL");
        return out;
    }

    private CandleBar bar(double open, double high, double low, double close) {
        return new CandleBar(Instant.now(), open, high, low, close);
    }

    /**
     * Used where bar content is irrelevant — trail logic reads cumulative MFE
     * from the outcome, not this bar. Only time matters (triggered_at stamp).
     */
    private CandleBar anyBar() {
        return new CandleBar(Instant.now(), 100, 100.01, 99.99, 100);
    }
}
