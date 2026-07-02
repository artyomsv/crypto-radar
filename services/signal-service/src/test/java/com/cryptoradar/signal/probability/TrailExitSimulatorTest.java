package com.cryptoradar.signal.probability;

import com.cryptoradar.core.TrailConfig;
import com.cryptoradar.signal.model.CandleBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-unit tests for the trailing-stop exit simulation. Entry 100, risk 1.5
 * (so 1R = 1.5 price units, stop at 100±1.5), ATR 1.0. Uses TrailConfig.DEFAULT
 * (activate 1R, step 0.5R, offset 0.5R, wider 1.0R @2.5R).
 */
class TrailExitSimulatorTest {

    private static final Instant T0 = Instant.parse("2026-06-28T00:00:00Z");
    private static final double ENTRY = 100.0;
    private static final double RISK = 1.5;
    private static final double ATR = 1.0;

    private List<CandleBar> bars(double[][] hiLoClose) {
        List<CandleBar> out = new ArrayList<>();
        for (int i = 0; i < hiLoClose.length; i++) {
            double hi = hiLoClose[i][0], lo = hiLoClose[i][1], cl = hiLoClose[i][2];
            out.add(new CandleBar(T0.plusSeconds(3600L * (i + 1)), cl, hi, lo, cl));
        }
        return out;
    }

    private TrailExitSimulator.Result sim(boolean isLong, List<CandleBar> bars) {
        return TrailExitSimulator.simulate(isLong, ENTRY, RISK, ATR, TrailConfig.DEFAULT, bars, T0);
    }

    @Test
    void v5EarlyTrailLocksSmallProfitAtHalfR() {
        // v5 config: activate 0.5R, step 0.05, offset 0.3 → locks +0.2R at 0.5R peak.
        // SHORT reaches 0.5R (price 99.25), activates trail at +0.2R (price 99.70),
        // then reverses up → exits at +0.2R instead of running back to the -1R stop.
        TrailConfig early = new TrailConfig(0.5, 0.05, 0.3);
        var r = TrailExitSimulator.simulate(false, ENTRY, RISK, ATR, early, bars(new double[][]{
                {99.90, 99.25, 99.50},   // MFE 0.5R → trail locks +0.2R
                {99.75, 99.60, 99.70}}), // favorable falls to +0.167R ≤ +0.2R trail → exit
                T0);
        assertTrue(r.resolved());
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET, r.status());
        assertEquals(100.0 - 0.2 * RISK, r.exitPrice(), 1e-9);   // 99.70 = +0.2R locked
    }

    @Test
    void shortInitialStopWhenPriceRisesPastStop() {
        // High 101.6 → favorable -1.07R → original stop (101.5) taken at a loss.
        var r = sim(false, bars(new double[][]{{101.6, 100.5, 101.5}}));
        assertTrue(r.resolved());
        assertEquals(ProbabilityCandidate.STATUS_HIT_STOP, r.status());
        assertEquals(101.5, r.exitPrice(), 1e-9);
    }

    @Test
    void shortActivatesAtOneRThenTrailsOutInProfit() {
        // Bar1 dips to 98.5 (=1R favorable) → trail advances to +0.5R; high 99.5 doesn't stop.
        // Bar2 high 99.3 → favorable falls to +0.467R ≤ trail 0.5R → exit locked at +0.5R.
        var r = sim(false, bars(new double[][]{
                {99.5, 98.5, 99.0},
                {99.3, 99.0, 99.2}}));
        assertTrue(r.resolved());
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET, r.status());
        assertEquals(100.0 - 0.5 * RISK, r.exitPrice(), 1e-9);   // 99.25
    }

    @Test
    void shortRunnerCapturesFarMoreThanOneR() {
        // Runs to 2R favorable (low 97.0) before retracing — trail rides up to +1.5R.
        var r = sim(false, bars(new double[][]{
                {99.5, 98.5, 99.0},   // mfeR 1.0 → trail 0.5
                {98.6, 97.0, 97.5},   // mfeR 2.0 → trail 1.5
                {99.1, 97.6, 99.0}})); // high 99.0 → favorable 0.667R ≤ 1.5R → exit +1.5R
        assertTrue(r.resolved());
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET, r.status());
        assertEquals(100.0 - 1.5 * RISK, r.exitPrice(), 1e-9);   // 97.75 — far past the 1:1 target of 98.5
        assertEquals(3.0, r.mfeAtr(), 1e-9);                      // (100-97)/1.0
    }

    @Test
    void unresolvedWhenPathEndsWithoutStop() {
        // Drifts favorable but never retraces by the offset and never hits stop.
        var r = sim(false, bars(new double[][]{
                {99.8, 99.2, 99.4},
                {99.4, 98.9, 99.0}})); // best 1.1R reached late; no offset retrace within path
        assertFalse(r.resolved());
        assertTrue(r.mfeAtr() > 0);
    }

    @Test
    void longRunnerMirrorsShort() {
        // LONG runs up to 2R (high 103.0) then retraces — trail rides to +1.5R.
        var r = sim(true, bars(new double[][]{
                {101.5, 100.5, 101.0},   // mfeR 1.0 → trail 0.5
                {103.0, 101.4, 102.5},   // mfeR 2.0 → trail 1.5
                {102.4, 101.0, 101.2}})); // low 101.0 → favorable 0.667R ≤ 1.5R → exit +1.5R
        assertTrue(r.resolved());
        assertEquals(ProbabilityCandidate.STATUS_HIT_TARGET, r.status());
        assertEquals(100.0 + 1.5 * RISK, r.exitPrice(), 1e-9);   // 102.25
    }
}
