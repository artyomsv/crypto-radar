package com.cryptoradar.whale.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for whale analytics calculation formulas.
 * These test the core math independently of database access.
 *
 * Formulas mirror WhaleAnalyticsService:
 *   pressure  = clamp((buyVol - sellVol) / (buyVol + sellVol) * 100, -100, 100)
 *   label     = Heavy Buying (>=50), Buying (>=15), Selling (<=-15), Heavy Selling (<=-50), else Neutral
 *   activity  = min(100, (tradeCount / BASELINE) * 100) where BASELINE = 20
 */
class WhaleAnalyticsCalculationTest {

    private static final int BASELINE_TRADES_PER_HOUR = 20;

    // Mirror the pressure formula from WhaleAnalyticsService
    private double calculatePressure(double buyVol, double sellVol) {
        double total = buyVol + sellVol;
        if (total == 0) return 0.0;
        return Math.max(-100.0, Math.min(100.0, (buyVol - sellVol) / total * 100.0));
    }

    // Mirror the label logic from WhaleAnalyticsService.pressureLabel
    private String pressureLabel(double pressure) {
        if (pressure >= 50) return "Heavy Buying";
        if (pressure >= 15) return "Buying";
        if (pressure <= -50) return "Heavy Selling";
        if (pressure <= -15) return "Selling";
        return "Neutral";
    }

    private int activityScore(int tradeCount) {
        return Math.min(100, (int) ((tradeCount / (double) BASELINE_TRADES_PER_HOUR) * 100));
    }

    // ---- Pressure calculation ----

    @Test
    void pressure_allBuys_returns100() {
        assertEquals(100.0, calculatePressure(1000000, 0), 0.01);
    }

    @Test
    void pressure_allSells_returnsNeg100() {
        assertEquals(-100.0, calculatePressure(0, 1000000), 0.01);
    }

    @Test
    void pressure_equalVolume_returnsZero() {
        assertEquals(0.0, calculatePressure(500000, 500000), 0.01);
    }

    @Test
    void pressure_zeroVolume_returnsZero() {
        assertEquals(0.0, calculatePressure(0, 0), 0.01);
    }

    @ParameterizedTest
    @CsvSource({
        "750000, 250000, 50.0",   // 75% buy
        "600000, 400000, 20.0",   // 60% buy
        "400000, 600000, -20.0",  // 40% buy
        "250000, 750000, -50.0",  // 25% buy
        "100000, 0, 100.0",       // all buy
        "0, 100000, -100.0",      // all sell
    })
    void pressure_variousRatios(double buyVol, double sellVol, double expected) {
        assertEquals(expected, calculatePressure(buyVol, sellVol), 0.01);
    }

    @Test
    void pressure_clampedToRange() {
        // Even with extreme values, should stay in [-100, 100]
        double result = calculatePressure(Double.MAX_VALUE, 0);
        assertTrue(result <= 100.0);
        assertTrue(result >= -100.0);
    }

    // ---- Pressure labels (thresholds match WhaleAnalyticsService) ----

    @Test
    void pressureLabel_heavyBuying() {
        assertEquals("Heavy Buying", pressureLabel(50));
        assertEquals("Heavy Buying", pressureLabel(100));
        assertEquals("Heavy Buying", pressureLabel(75));
    }

    @Test
    void pressureLabel_buying() {
        assertEquals("Buying", pressureLabel(15));
        assertEquals("Buying", pressureLabel(49));
        assertEquals("Buying", pressureLabel(30));
    }

    @Test
    void pressureLabel_neutral() {
        assertEquals("Neutral", pressureLabel(0));
        assertEquals("Neutral", pressureLabel(14));
        assertEquals("Neutral", pressureLabel(-14));
    }

    @Test
    void pressureLabel_selling() {
        assertEquals("Selling", pressureLabel(-15));
        assertEquals("Selling", pressureLabel(-49));
    }

    @Test
    void pressureLabel_heavySelling() {
        assertEquals("Heavy Selling", pressureLabel(-50));
        assertEquals("Heavy Selling", pressureLabel(-100));
    }

    // ---- Activity score (baseline = 20 trades/hour) ----

    @Test
    void activityScore_halfBaseline() {
        assertEquals(50, activityScore(10));
    }

    @Test
    void activityScore_atBaseline() {
        assertEquals(100, activityScore(20));
    }

    @Test
    void activityScore_aboveBaseline_cappedAt100() {
        assertEquals(100, activityScore(50));
    }

    @Test
    void activityScore_zeroTrades() {
        assertEquals(0, activityScore(0));
    }

    @Test
    void activityScore_shouldBeInRange() {
        for (int count = 0; count <= 100; count++) {
            int score = activityScore(count);
            assertTrue(score >= 0 && score <= 100,
                    "Score " + score + " out of range for tradeCount=" + count);
        }
    }

    // ---- Net flow ----

    @Test
    void netFlow_calculation() {
        double buyVol = 150000;
        double sellVol = 80000;
        double netFlow = buyVol - sellVol;
        assertEquals(70000, netFlow, 0.01);
        assertTrue(netFlow > 0, "Positive net flow means more buying");
    }

    @Test
    void netFlow_negative_meansMoreSelling() {
        double buyVol = 30000;
        double sellVol = 120000;
        double netFlow = buyVol - sellVol;
        assertEquals(-90000, netFlow, 0.01);
        assertTrue(netFlow < 0, "Negative net flow means more selling");
    }
}
