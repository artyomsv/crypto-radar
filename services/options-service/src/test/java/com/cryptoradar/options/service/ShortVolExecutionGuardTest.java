package com.cryptoradar.options.service;

import com.cryptoradar.options.model.OptionShortVolOpportunity;
import com.cryptoradar.options.repository.OptionShortVolOpportunityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 4 binding-constraints contract. Each test pins one of the
 * pre-registered constraints from the strategy plan — a future refactor
 * that loosens any of them should make a test fail and force a deliberate
 * approval flow.
 */
class ShortVolExecutionGuardTest {

    private ShortVolExecutionGuard guard;
    private OptionShortVolOpportunity candidate;

    @BeforeEach
    void setUp() {
        guard = new ShortVolExecutionGuard();
        // Default to "feature on" so tests can isolate the OTHER constraints.
        // The flag-off case is its own test below.
        guard.executionEnabled = true;
        guard.maxConcurrent = 2;
        guard.maxRiskPctOfEquity = 0.5;
        guard.maxCombinedVega = 500.0;
        guard.repo = new FakeRepo(0);

        candidate = new OptionShortVolOpportunity();
        candidate.setMaxLossUsd(2.0);   // 0.4% of $500 equity by default — under 0.5% limit
        candidate.setNetCredit(0.5);
    }

    @Test
    void featureFlagOffAlwaysDenies() {
        guard.executionEnabled = false;
        ShortVolExecutionGuard.Decision d = guard.shouldExecute(candidate, 500.0, 0.0);
        assertFalse(d.approved());
        assertEquals("feature_flag_off", d.code());
    }

    @Test
    void cleanCandidateApprovedWhenFlagOn() {
        ShortVolExecutionGuard.Decision d = guard.shouldExecute(candidate, 500.0, 0.0);
        assertTrue(d.approved(), "clean candidate should approve: " + d.message());
    }

    @Test
    void maxConcurrentReached() {
        guard.repo = new FakeRepo(2);   // already 2 open
        ShortVolExecutionGuard.Decision d = guard.shouldExecute(candidate, 500.0, 0.0);
        assertFalse(d.approved());
        assertEquals("max_concurrent_reached", d.code());
    }

    @Test
    void riskPerTradeExceededDenies() {
        candidate.setMaxLossUsd(50.0);   // 10% of $500 equity — well over 0.5% limit
        ShortVolExecutionGuard.Decision d = guard.shouldExecute(candidate, 500.0, 0.0);
        assertFalse(d.approved());
        assertEquals("risk_per_trade_exceeded", d.code());
    }

    @Test
    void combinedVegaExceededDenies() {
        candidate.setMaxLossUsd(2.0);     // small per-trade
        ShortVolExecutionGuard.Decision d = guard.shouldExecute(candidate, 500.0, 499.99);
        assertFalse(d.approved());
        assertEquals("combined_vega_exceeded", d.code());
    }

    @Test
    void boundaryAtMaxConcurrent() {
        guard.repo = new FakeRepo(1);   // 1 open, limit 2 → next is allowed
        ShortVolExecutionGuard.Decision d = guard.shouldExecute(candidate, 500.0, 0.0);
        assertTrue(d.approved());
    }

    // Minimal repo stub returning N open positions.
    private static class FakeRepo extends OptionShortVolOpportunityRepository {
        private final int openCount;
        FakeRepo(int openCount) { this.openCount = openCount; }
        @Override
        public List<OptionShortVolOpportunity> findOpen(int limit) {
            return java.util.Collections.nCopies(openCount, new OptionShortVolOpportunity());
        }
    }
}
