package com.cryptoradar.options.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the deflated-Sharpe math. The formula has three failure modes that
 * are easy to introduce in a refactor:
 *
 * <ol>
 *   <li>{@code expectedMaxSharpe} grows with trial count — denominator
 *       should be HARDER to beat as we test more variants</li>
 *   <li>Inverse normal CDF correctness at extreme percentiles</li>
 *   <li>DSR verdict thresholds (NO_EDGE / INCONCLUSIVE / EDGE_CONFIRMED)</li>
 * </ol>
 */
class DeflatedSharpeEvaluatorTest {

    private final DeflatedSharpeEvaluator ev = newEvaluator();

    private static DeflatedSharpeEvaluator newEvaluator() {
        DeflatedSharpeEvaluator e = new DeflatedSharpeEvaluator();
        // @ConfigProperty defaults aren't applied for plain JUnit construction
        // — wire the same values the application.properties would inject.
        e.trialCount = 20;
        e.lookbackTrades = 100;
        return e;
    }

    @Test
    void normalCdfBasicValues() {
        // Φ(0) = 0.5
        assertEquals(0.5, DeflatedSharpeEvaluator.normalCdf(0), 1e-3);
        // Φ(1.96) ≈ 0.975
        assertEquals(0.975, DeflatedSharpeEvaluator.normalCdf(1.96), 1e-2);
        // Φ(-1.96) ≈ 0.025
        assertEquals(0.025, DeflatedSharpeEvaluator.normalCdf(-1.96), 1e-2);
    }

    @Test
    void normalInvCdfBasicValues() {
        // Φ⁻¹(0.5) = 0
        assertEquals(0.0, DeflatedSharpeEvaluator.normalInvCdf(0.5), 1e-6);
        // Φ⁻¹(0.975) ≈ 1.96
        assertEquals(1.96, DeflatedSharpeEvaluator.normalInvCdf(0.975), 1e-3);
    }

    @Test
    void expectedMaxSharpeGrowsWithTrialCount() {
        // More trials → harder bar — Bailey/LdP eq. 8 monotonicity.
        double tenTrials = DeflatedSharpeEvaluator.expectedMaxSharpeUnderNull(10);
        double hundredTrials = DeflatedSharpeEvaluator.expectedMaxSharpeUnderNull(100);
        double thousandTrials = DeflatedSharpeEvaluator.expectedMaxSharpeUnderNull(1000);
        assertTrue(hundredTrials > tenTrials, "expected max sharpe should grow with N");
        assertTrue(thousandTrials > hundredTrials, "expected max sharpe should grow with N");
    }

    @Test
    void insufficientSampleProducesInsufficientVerdict() {
        DeflatedSharpeEvaluator.Result r = ev.computeDsr(List.of(1.0, 2.0, 3.0), "test");
        assertEquals("INSUFFICIENT_SAMPLE", r.verdict);
        assertEquals(3, r.n);
    }

    @Test
    void strongPositiveEdgeProducesEdgeConfirmedVerdict() {
        // Stream of strongly-positive returns: mean=2, std≈0.5, sharpe≈4 —
        // comfortably above expected-max-sharpe-under-null for 20 trials
        // (which sits at ~1.9). Verifies DSR crosses the 0.95 EDGE bar.
        List<Double> returns = IntStream.range(0, 50)
                .mapToObj(i -> i % 2 == 0 ? 2.5 : 1.5)
                .toList();
        DeflatedSharpeEvaluator.Result r = ev.computeDsr(returns, "long-vol");
        assertEquals(50, r.n);
        assertTrue(r.sharpe > 3.0, "raw Sharpe should be high: " + r.sharpe);
        assertTrue(r.deflatedSharpe > 0.95,
                "DSR should be > 0.95 for clean positive edge: " + r.deflatedSharpe);
        assertEquals("EDGE_CONFIRMED", r.verdict);
    }

    @Test
    void noEdgeProducesNoEdgeVerdict() {
        // Roughly zero-mean returns — alternating +1 / −1 over 50 samples.
        // Sharpe ≈ 0 → DSR low → verdict NO_EDGE.
        List<Double> returns = IntStream.range(0, 50)
                .mapToObj(i -> i % 2 == 0 ? 1.0 : -1.0)
                .toList();
        DeflatedSharpeEvaluator.Result r = ev.computeDsr(returns, "test");
        assertTrue(Math.abs(r.mean) < 0.01, "mean should be ~0: " + r.mean);
        assertTrue(r.deflatedSharpe < 0.5, "DSR should be < 0.5 for no edge: " + r.deflatedSharpe);
        assertEquals("NO_EDGE", r.verdict);
    }
}
