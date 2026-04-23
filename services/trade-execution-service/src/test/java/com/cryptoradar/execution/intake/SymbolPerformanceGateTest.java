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
 * JDBC wiring is validated separately by live smoke (Docker rebuild).
 */
class SymbolPerformanceGateTest {

    private StubGate gate;

    @BeforeEach
    void setUp() {
        gate = new StubGate();
        gate.enabled = true;
        gate.lookback = 10;
        gate.thresholdR = -3.0;
        gate.cacheTtlSeconds = 60;
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
        gate.enabled = false;
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
        gate.cacheTtlSeconds = 0;
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

    /**
     * Subclass overrides {@code queryTotalR} to return a stubbed value,
     * bypassing the JDBC path. Pure unit tests without Quarkus or a DB.
     */
    private static class StubGate extends SymbolPerformanceGate {
        Double stubbedTotalR;
        final AtomicInteger queryCount = new AtomicInteger();

        @Override
        Double queryTotalR(String symbol) {
            queryCount.incrementAndGet();
            return stubbedTotalR;
        }
    }
}
