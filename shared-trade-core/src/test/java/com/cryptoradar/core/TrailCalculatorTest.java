package com.cryptoradar.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailCalculatorTest {

    // Explicit single-rung defaults. TrailConfig.DEFAULT now ships Vector F's
    // wider offset at MFE ≥ 2.5R; these tests pre-date that change and pin the
    // basic ladder math for MFE below the second-rung threshold.
    private static final TrailConfig DEFAULTS = new TrailConfig(1.0, 0.5, 0.5);

    @Test
    void belowActivationReturnsEmpty() {
        Optional<Double> result = TrailCalculator.computeNewTrailR(0.5, DEFAULTS, 0.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void exactlyAtActivationReturnsActivationMinusOffset() {
        // mfeR=1.0, activation=1.0 → rung=0, newTrailR = 1.0 + 0*0.5 - 0.5 = 0.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.0, DEFAULTS, 0.0);
        assertEquals(0.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void aboveActivationAdvancesByFullRungs() {
        // mfeR=1.7, activation=1.0 → rung=floor(0.7/0.5)=1, newTrailR = 1.0 + 1*0.5 - 0.5 = 1.0
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.7, DEFAULTS, 0.0);
        assertEquals(1.0, result.orElseThrow(), 1e-9);
    }

    @Test
    void twoFullRungsAbove() {
        // mfeR=2.2, activation=1.0 → rung=floor(1.2/0.5)=2, newTrailR = 1.0 + 2*0.5 - 0.5 = 1.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(2.2, DEFAULTS, 0.0);
        assertEquals(1.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void returnsEmptyWhenCurrentHighestAlreadyReached() {
        // mfeR=1.7 would compute newTrailR=1.0, but currentHighestR=1.0 already
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.7, DEFAULTS, 1.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenCurrentHighestExceedsWhatMfeImplies() {
        // Monotonic: price pulled back, mfeR=1.2, but we previously ratcheted to 1.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(1.2, DEFAULTS, 1.5);
        assertTrue(result.isEmpty());
    }

    @Test
    void advancesFromNonZeroHighest() {
        // mfeR=3.2, activation=1.0 → rung=floor(2.2/0.5)=4, newTrailR = 1.0 + 4*0.5 - 0.5 = 2.5
        // currentHighest=1.5, so advance to 2.5
        Optional<Double> result = TrailCalculator.computeNewTrailR(3.2, DEFAULTS, 1.5);
        assertEquals(2.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void customConfigRespected() {
        // activation=2.0, step=1.0, offset=0.0
        TrailConfig config = new TrailConfig(2.0, 1.0, 0.0);
        // mfeR=3.5 → rung=floor(1.5/1.0)=1, newTrailR = 2.0 + 1*1.0 - 0.0 = 3.0
        Optional<Double> result = TrailCalculator.computeNewTrailR(3.5, config, 0.0);
        assertEquals(3.0, result.orElseThrow(), 1e-9);
    }

    @Test
    void zeroOffsetConfigParksTrailAtExactRung() {
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.0);
        // mfeR=2.0 → rung=floor(1.0/0.5)=2, newTrailR = 1.0 + 2*0.5 - 0.0 = 2.0
        Optional<Double> result = TrailCalculator.computeNewTrailR(2.0, config, 0.0);
        assertEquals(2.0, result.orElseThrow(), 1e-9);
    }

    // --- Vector F two-rung tests ---

    @Test
    void widerOffsetInactiveBelowThreshold() {
        // With two-rung config 1/0.5/0.5/2.5/1.0 and MFE=2.2 (below 2.5 threshold),
        // the tight offsetR=0.5 applies. rung=floor(1.2/0.5)=2 → 1.0+2*0.5-0.5 = 1.5
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.5, 2.5, 1.0);
        Optional<Double> result = TrailCalculator.computeNewTrailR(2.2, config, 0.0);
        assertEquals(1.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void widerOffsetKicksInAtThreshold() {
        // MFE=2.5 crosses the second-rung threshold. Wider offsetR=1.0 applies.
        // rung=floor(1.5/0.5)=3 → 1.0+3*0.5-1.0 = 1.5
        // Compared to single-rung math which would give 1.0+3*0.5-0.5 = 2.0.
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.5, 2.5, 1.0);
        Optional<Double> result = TrailCalculator.computeNewTrailR(2.5, config, 0.0);
        assertEquals(1.5, result.orElseThrow(), 1e-9);
    }

    @Test
    void widerOffsetDeepInRightTail() {
        // MFE=4.0, deep in the right tail. Wider offsetR=1.0.
        // rung=floor(3.0/0.5)=6 → 1.0+6*0.5-1.0 = 3.0
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.5, 2.5, 1.0);
        Optional<Double> result = TrailCalculator.computeNewTrailR(4.0, config, 0.0);
        assertEquals(3.0, result.orElseThrow(), 1e-9);
    }

    @Test
    void widerOffsetGivesMoreRoomThanSingleRung() {
        // Same MFE, two configs — wider-offset config produces a lower
        // (more permissive) trail R. Proves Vector F mechanically gives
        // runners more room once past the threshold.
        TrailConfig tight = new TrailConfig(1.0, 0.5, 0.5);
        TrailConfig wider = new TrailConfig(1.0, 0.5, 0.5, 2.5, 1.0);

        double tightTrail = TrailCalculator.computeNewTrailR(3.0, tight, 0.0).orElseThrow();
        double widerTrail = TrailCalculator.computeNewTrailR(3.0, wider, 0.0).orElseThrow();

        assertTrue(widerTrail < tightTrail,
                "wider offset should produce lower trail R; got tight=" + tightTrail + " wider=" + widerTrail);
    }

    @Test
    void disabledWiderOffsetMatchesLegacy() {
        // widerOffsetActivationR=0 means the second rung is disabled;
        // calculator must fall back to the original single-offset ladder.
        TrailConfig legacy = new TrailConfig(1.0, 0.5, 0.5);
        TrailConfig disabledWider = new TrailConfig(1.0, 0.5, 0.5, 0.0, 0.0);

        for (double mfe : new double[]{0.5, 1.0, 1.5, 2.0, 3.0, 5.0}) {
            Optional<Double> legacyResult = TrailCalculator.computeNewTrailR(mfe, legacy, 0.0);
            Optional<Double> disabledResult = TrailCalculator.computeNewTrailR(mfe, disabledWider, 0.0);
            assertEquals(legacyResult, disabledResult,
                    "parity required at mfeR=" + mfe);
        }
    }

    @Test
    void monotonicAcrossOffsetSwitch() {
        // Ensures the trail never loosens when crossing the widerOffset threshold.
        // At MFE=2.4 trail was at 1.5R (offset=0.5). At MFE=2.5 with offset=1.0,
        // the new computation yields 1.5R — equal, not greater, so empty.
        TrailConfig config = new TrailConfig(1.0, 0.5, 0.5, 2.5, 1.0);
        Optional<Double> result = TrailCalculator.computeNewTrailR(2.5, config, 1.5);
        assertTrue(result.isEmpty(), "must not loosen; got " + result);
    }
}
