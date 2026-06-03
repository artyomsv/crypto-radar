package com.cryptoradar.signal.backtest;

import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.model.SignalOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-function tests for the cheap-fidelity trail re-walk used in Tier 1
 * backtests. Verifies the algorithm against the same TrailCalculator
 * semantics OutcomeEvaluator uses in production.
 */
class BacktestServiceTrailTest {

    private static final SignalConfig.Trail SINGLE_RUNG =
            new SignalConfig.Trail(1.0, 0.5, 0.5, 0.0, 0.0);
    private static final SignalConfig.Trail TWO_RUNG =
            new SignalConfig.Trail(1.0, 0.5, 0.5, 2.5, 1.0);

    @Test
    void mfeBelowActivationReturnsMinusOne() {
        // Recorded outcome closed via TRAIL_STOP but the NEW activation
        // threshold (1R) was never reached. Approximate as the canonical
        // 1R loss.
        SignalOutcome o = outcome(100.0, 99.0, 0.5);
        Double r = BacktestService.simulateTrailExitR(o, SINGLE_RUNG);
        assertEquals(-1.0, r, 0.001);
    }

    @Test
    void mfeAtActivationReturnsActivationMinusOffset() {
        // MFE = 1R (matches activation). Highest rung = 1.0; trail sits
        // 0.5R behind = 0.5R.
        SignalOutcome o = outcome(100.0, 99.0, 1.0);
        Double r = BacktestService.simulateTrailExitR(o, SINGLE_RUNG);
        assertEquals(0.5, r, 0.001);
    }

    @Test
    void mfeAtTwoRRatchetsToOneR() {
        // MFE = 2R. rung = floor((2-1)/0.5) = 2 → highest = 1 + 2*0.5 = 2.0
        // → trail sits at 2.0 - 0.5 = 1.5R.
        SignalOutcome o = outcome(100.0, 99.0, 2.0);
        Double r = BacktestService.simulateTrailExitR(o, SINGLE_RUNG);
        assertEquals(1.5, r, 0.001);
    }

    @Test
    void widerOffsetEngagesPastSecondActivation() {
        // MFE = 3R. rung = floor((3-1)/0.5) = 4 → highest = 1 + 4*0.5 = 3.0
        // wider activation (2.5) crossed → offset widens to 1.0
        // → trail sits at 3.0 - 1.0 = 2.0R.
        SignalOutcome o = outcome(100.0, 99.0, 3.0);
        Double r = BacktestService.simulateTrailExitR(o, TWO_RUNG);
        assertEquals(2.0, r, 0.001);
    }

    @Test
    void tightOffsetBeforeWiderActivation() {
        // MFE = 2R, two-rung config but MFE hasn't crossed widerActivation
        // (2.5). Tight 0.5R offset still applies → 2.0 - 0.5 = 1.5.
        SignalOutcome o = outcome(100.0, 99.0, 2.0);
        Double r = BacktestService.simulateTrailExitR(o, TWO_RUNG);
        assertEquals(1.5, r, 0.001);
    }

    @Test
    void shortDirectionUsesAbsoluteRisk() {
        // SHORT: entry 100, stop 101 (above). risk distance = 1, MFE pct
        // 1.0 → mfeR = 1.0 → trail at 0.5R same as long.
        SignalOutcome o = new SignalOutcome();
        o.setEntryPrice(100.0);
        o.setStopPrice(101.0);
        o.setMaxFavorablePct(1.0);
        o.setDirection("SHORT");
        o.setFinalExitReason("TRAIL_STOP");
        Double r = BacktestService.simulateTrailExitR(o, SINGLE_RUNG);
        assertEquals(0.5, r, 0.001);
    }

    @Test
    void missingEntryReturnsNull() {
        SignalOutcome o = new SignalOutcome();
        o.setStopPrice(99.0);
        o.setMaxFavorablePct(2.0);
        assertNull(BacktestService.simulateTrailExitR(o, SINGLE_RUNG));
    }

    @Test
    void zeroMfeFallsBackToActivationFloor() {
        // SignalOutcome.maxFavorablePct defaults to 0.0 (not null), so an
        // outcome that never moved favourably reads mfeR=0 < activationR
        // and returns the canonical -1R "trail never engaged" outcome.
        SignalOutcome o = new SignalOutcome();
        o.setEntryPrice(100.0);
        o.setStopPrice(99.0);
        assertEquals(-1.0, BacktestService.simulateTrailExitR(o, SINGLE_RUNG), 0.001);
    }

    @Test
    void zeroRiskReturnsNull() {
        // entry == stop → undefined R unit.
        SignalOutcome o = outcome(100.0, 100.0, 2.0);
        assertNull(BacktestService.simulateTrailExitR(o, SINGLE_RUNG));
    }

    @Test
    void resultIsAlwaysFinite() {
        // Defence against future regressions: any sensible input must
        // produce a finite Double, never NaN/Infinity.
        SignalOutcome o = outcome(50_000.0, 49_500.0, 5.5);
        Double r = BacktestService.simulateTrailExitR(o, TWO_RUNG);
        assertNotNull(r);
        assertEquals(true, Double.isFinite(r));
    }

    private static SignalOutcome outcome(double entry, double stop, double mfePct) {
        SignalOutcome o = new SignalOutcome();
        o.setEntryPrice(entry);
        o.setStopPrice(stop);
        o.setMaxFavorablePct(mfePct);
        o.setDirection("LONG");
        o.setFinalExitReason("TRAIL_STOP");
        return o;
    }
}
