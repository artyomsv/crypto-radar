package com.cryptoradar.execution.intake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the per-cell sizing bucket thresholds. These are load-bearing for
 * the compounding-the-winners strategy — a regression here silently re-sizes
 * every executed trade.
 */
class StrategyPerformanceSizerTest {

    @Test
    void insufficientSampleReturnsNeutral() {
        // n<5 → no opinion regardless of R
        assertEquals(1.0, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(4, 100.0)));
        assertEquals(1.0, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(0, -50.0)));
    }

    @Test
    void strongWinnerSizesUp_15x() {
        // n>=5 AND total_R >= +5 → 1.5x
        assertEquals(1.5, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(10, 12.76)));
        assertEquals(1.5, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(5, 5.0)));
    }

    @Test
    void moderateWinnerSizesUp_125x() {
        // n>=5 AND +2 <= total_R < +5 → 1.25x
        assertEquals(1.25, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(10, 3.5)));
        assertEquals(1.25, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(8, 2.0)));
    }

    @Test
    void weakLoserSizesDown_05x() {
        // n>=5 AND total_R <= -1 → 0.5x (still above SymbolPerformanceGate's -3R floor)
        assertEquals(0.5, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(10, -2.5)));
        assertEquals(0.5, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(5, -1.01)));
    }

    @Test
    void mildlyPositiveOrFlatStaysNeutral() {
        // n>=5 AND -1 < total_R < +2 → 1.0x
        assertEquals(1.0, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(10, 0.5)));
        assertEquals(1.0, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(10, -0.5)));
        assertEquals(1.0, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(10, 1.99)));
    }

    @Test
    void boundaryCases() {
        // Exactly at threshold goes to the higher bucket
        assertEquals(1.5, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(5, 5.0)));
        // Just below threshold drops a bucket
        assertEquals(1.25, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(5, 4.99)));
        // n=5 is the minimum-eligible sample
        assertEquals(1.5, StrategyPerformanceSizer.decideMultiplier(new StrategyPerformanceSizer.Stats(5, 5.0)));
    }
}
