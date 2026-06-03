package com.cryptoradar.execution.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vector A — unit tests for gate logic. Exercises threshold decision,
 * fail-open on query failure, cache hits across calls, and the
 * {@code enabled} kill-switch without spinning up Quarkus or a DB.
 * Gate now reads its tunables from {@link ExecutionSettingsService}; tests
 * inject a stub snapshot.
 */
class SymbolPerformanceGateTest {

    private StubGate gate;
    private MutableSnapshotHolder settingsRef;

    @BeforeEach
    void setUp() {
        settingsRef = new MutableSnapshotHolder();
        gate = new StubGate();
        // Anonymous subclass keeps Quarkus Arc from discovering it as a
        // candidate bean (named test-class subclasses caused ambiguous
        // injection failures in @QuarkusTest integration suites).
        gate.executionSettings = new ExecutionSettingsService(null, null) {
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
    void symbolAboveThresholdIsNotSuppressed() {
        gate.stubbedTotalR = 1.5;
        assertFalse(gate.isSuppressed("BTCUSDT"));
    }

    @Test
    void symbolAtThresholdIsSuppressed() {
        gate.stubbedTotalR = -3.0;
        assertTrue(gate.isSuppressed("LTCUSDT"));
    }

    @Test
    void symbolBelowThresholdIsSuppressed() {
        gate.stubbedTotalR = -6.49;
        assertTrue(gate.isSuppressed("LTCUSDT"));
    }

    @Test
    void queryFailureFailsOpen() {
        gate.stubbedTotalR = null;
        assertFalse(gate.isSuppressed("ETHUSDT"));
    }

    @Test
    void disabledGateNeverSuppresses() {
        settingsRef.snap = snapshot(false, 10, -3.0, 60);
        gate.stubbedTotalR = -99.0;
        assertFalse(gate.isSuppressed("LTCUSDT"));
    }

    @Test
    void cacheHitAvoidsRepeatedQueries() {
        gate.stubbedTotalR = -6.49;
        assertTrue(gate.isSuppressed("LTCUSDT"));
        assertTrue(gate.isSuppressed("LTCUSDT"));
        assertTrue(gate.isSuppressed("LTCUSDT"));
        assertEquals(1, gate.queryCount.get(),
                "gate must cache and not re-query within the TTL window");
    }

    @Test
    void differentSymbolsAreCachedIndependently() {
        gate.stubbedTotalR = -6.49;
        assertTrue(gate.isSuppressed("LTCUSDT"));
        gate.stubbedTotalR = 2.0;
        assertFalse(gate.isSuppressed("BTCUSDT"));
        assertEquals(2, gate.queryCount.get(),
                "each distinct symbol triggers one query");
    }

    @Test
    void cacheExpiryTriggersReevaluation() {
        settingsRef.snap = snapshot(true, 10, -3.0, 0);
        gate.stubbedTotalR = -6.49;
        assertTrue(gate.isSuppressed("LTCUSDT"));
        gate.stubbedTotalR = 2.0;
        assertFalse(gate.isSuppressed("LTCUSDT"));
    }

    @Test
    void lastDecisionReflectsCachedState() {
        gate.stubbedTotalR = -6.49;
        gate.isSuppressed("LTCUSDT");
        SymbolPerformanceGate.CachedDecision decision = gate.lastDecisionFor("LTCUSDT");
        assertTrue(decision.suppressed());
        assertEquals(-6.49, decision.totalR(), 0.001);
        assertEquals(10, decision.sampleSize());
    }

    private static ExecutionSettingsService.Snapshot snapshot(
            boolean enabled, int lookback, double thresholdR, int cacheTtlSec) {
        return new ExecutionSettingsService.Snapshot(
                70, enabled, lookback, thresholdR, cacheTtlSec, true, 15, 60,
                false, null, ExecutionSettingsService.DEFAULT_NOTIFIED_EVENTS, false, null, false);
    }

    /**
     * Subclass overrides {@code queryTotalR} to return a stubbed value,
     * bypassing the JDBC path. Pure unit tests without Quarkus or a DB.
     */
    private static class StubGate extends SymbolPerformanceGate {
        Double stubbedTotalR;
        final AtomicInteger queryCount = new AtomicInteger();

        @Override
        Double queryTotalR(String symbol, int lookback) {
            queryCount.incrementAndGet();
            return stubbedTotalR;
        }
    }
}
