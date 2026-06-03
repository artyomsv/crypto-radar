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
    private MutableSnapshotHolder settingsRef;

    @BeforeEach
    void setUp() {
        settingsRef = new MutableSnapshotHolder();
        check = new DetectorConfluenceCheck();
        // Anonymous subclass — Quarkus Arc skips local types, so this
        // doesn't pollute the CDI bean catalog with an ambiguous candidate
        // for @QuarkusTest integration suites.
        check.executionSettings = new ExecutionSettingsService(null, null) {
            @Override
            public Snapshot snapshot() {
                return settingsRef.snap;
            }
        };
    }

    private static final class MutableSnapshotHolder {
        ExecutionSettingsService.Snapshot snap = ExecutionSettingsService.Snapshot.defaults();
    }

    @Test
    void trendContinuationLongRequiresConfluence() {
        assertTrue(check.requiresConfluence("trend-continuation", "LONG"));
    }

    @Test
    void trendContinuationShortAlsoRequiresConfluence() {
        // v4 SHORT-gap fix unblocked SELL signals; the first 4 v4 SHORTs
        // all lost. trend-continuation SHORT in BULL hits the same
        // counter-trend trap LONGs hit at local tops, so the gate
        // mirrors symmetrically.
        assertTrue(check.requiresConfluence("trend-continuation", "SHORT"));
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
        settingsRef.snap = new ExecutionSettingsService.Snapshot(
                70, true, 10, -3.0, 30, false, 15, 60,
                false, null, ExecutionSettingsService.DEFAULT_NOTIFIED_EVENTS, false, null, false);
        assertFalse(check.requiresConfluence("trend-continuation", "LONG"));
        assertFalse(check.requiresConfluence("trend-continuation", "SHORT"));
    }

    @Test
    void unknownStrategyNotFiltered() {
        assertFalse(check.requiresConfluence("some-future-detector", "LONG"));
        assertFalse(check.requiresConfluence(null, "LONG"));
    }

}
