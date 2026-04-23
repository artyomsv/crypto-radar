package com.cryptoradar.execution.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vector B — unit tests for confluence requirement logic. Covers every
 * combination of {strategy, direction} against the configured switch.
 * JDBC round-trip is exercised by live smoke rather than a full
 * Quarkus/DB setup — the query itself is trivial.
 */
class DetectorConfluenceCheckTest {

    private DetectorConfluenceCheck check;

    @BeforeEach
    void setUp() {
        check = new DetectorConfluenceCheck();
        check.trendContinuationBuyRequiresConfluence = true;
        check.windowMinutes = 15;
    }

    @Test
    void trendContinuationLongRequiresConfluence() {
        assertTrue(check.requiresConfluence("trend-continuation", "LONG"));
    }

    @Test
    void trendContinuationShortIsNotFiltered() {
        // Phase 2 losses concentrate in LONG. Leave SHORT alone so the
        // detector can still short a downtrend if one appears.
        assertFalse(check.requiresConfluence("trend-continuation", "SHORT"));
    }

    @Test
    void liquiditySweepIsNotFiltered() {
        // LS had only 1 trade in Phase 2 (+1.87R). Don't gate a detector
        // with insufficient negative signal.
        assertFalse(check.requiresConfluence("liquidity-sweep", "LONG"));
        assertFalse(check.requiresConfluence("liquidity-sweep", "SHORT"));
    }

    @Test
    void dimensionScoringIsNotFiltered() {
        // dimension-scoring IS the confluence reference — can't require itself.
        assertFalse(check.requiresConfluence("dimension-scoring", "LONG"));
        assertFalse(check.requiresConfluence("dimension-scoring", "SHORT"));
    }

    @Test
    void killSwitchDisablesAllFiltering() {
        check.trendContinuationBuyRequiresConfluence = false;
        assertFalse(check.requiresConfluence("trend-continuation", "LONG"));
    }

    @Test
    void unknownStrategyNotFiltered() {
        assertFalse(check.requiresConfluence("some-future-detector", "LONG"));
        assertFalse(check.requiresConfluence(null, "LONG"));
    }
}
