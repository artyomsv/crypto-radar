package com.cryptoradar.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailCalculatorTest {

    private static final TrailConfig DEFAULTS = TrailConfig.DEFAULT;   // activation=1.0 step=0.5 offset=0.5

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
}
