package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for the probability gate: candidate geometry, the logistic
 * win model's math, and calibration bucketing. All inputs are hardcoded formula
 * fixtures — no values become user-visible state.
 */
class ProbabilityGateTest {

    private static final double EPS = 1e-9;

    // ---- CandidateBuilder geometry ----

    @Test
    void longCandidateHasStopBelowAndTargetAboveAtTwoToOne() {
        Candidate c = CandidateBuilder.build(Candidate.LONG, 100.0, 4.0, 1.5, 2.0); // risk = 1.5*4 = 6
        assertEquals(94.0, c.stop(), EPS);
        assertEquals(112.0, c.target(), EPS);   // entry + 2*risk
        assertEquals(2.0, c.riskReward(), EPS);
        assertTrue(c.isLong());
    }

    @Test
    void shortCandidateMirrorsGeometry() {
        Candidate c = CandidateBuilder.build(Candidate.SHORT, 100.0, 4.0, 1.5, 2.0); // risk = 6
        assertEquals(106.0, c.stop(), EPS);
        assertEquals(88.0, c.target(), EPS);
        assertEquals(2.0, c.riskReward(), EPS);
    }

    @Test
    void oneToOneGeometryPutsTargetAtRiskDistance() {
        Candidate c = CandidateBuilder.build(Candidate.LONG, 100.0, 4.0, 1.5, 1.0); // risk 6, reward 6
        assertEquals(94.0, c.stop(), EPS);
        assertEquals(106.0, c.target(), EPS);
        assertEquals(1.0, c.riskReward(), EPS);
    }

    @Test
    void riskIsFlooredAtMinRiskPctWhenAtrIsTiny() {
        // 1.5*0.1 = 0.15 < MIN_RISK_PCT*100 = 1.5 -> risk floored to 1.5
        Candidate c = CandidateBuilder.build(Candidate.LONG, 100.0, 0.1, 1.5, 2.0);
        assertEquals(98.5, c.stop(), EPS);
        assertEquals(103.0, c.target(), EPS);
    }

    @Test
    void rejectsNonPositiveInputsAndBadDirection() {
        assertThrows(IllegalArgumentException.class, () -> CandidateBuilder.build(Candidate.LONG, 0, 1, 1.5, 2.0));
        assertThrows(IllegalArgumentException.class, () -> CandidateBuilder.build(Candidate.LONG, 100, 0, 1.5, 2.0));
        assertThrows(IllegalArgumentException.class, () -> CandidateBuilder.build("SIDEWAYS", 100, 1, 1.5, 2.0));
    }

    // ---- LogisticWinModel ----

    @Test
    void sigmoidMidpointIsHalf() {
        assertEquals(0.5, LogisticWinModel.sigmoid(0.0), EPS);
    }

    @Test
    void untrainedModelReturnsNoInformationHalf() {
        assertEquals(0.5, new LogisticWinModel().predict(new double[6]), EPS);
        assertFalse(new LogisticWinModel().isTrained());
    }

    @Test
    void trainsToSeparateAClearlyLinearlySeparableSignal() {
        // Technical score (feature 0) perfectly predicts the win label here.
        double[][] x = {
                {80, 0, 0, 0, 0, 0}, {70, 0, 0, 0, 0, 0}, {90, 0, 0, 0, 0, 0},
                {-80, 0, 0, 0, 0, 0}, {-70, 0, 0, 0, 0, 0}, {-90, 0, 0, 0, 0, 0}
        };
        int[] y = {1, 1, 1, 0, 0, 0};
        LogisticWinModel model = new LogisticWinModel();
        model.train(x, y, 2000, 0.5, 0.0);
        assertTrue(model.isTrained());
        assertTrue(model.predict(new double[]{85, 0, 0, 0, 0, 0}) > 0.5,
                "bullish technical should predict win");
        assertTrue(model.predict(new double[]{-85, 0, 0, 0, 0, 0}) < 0.5,
                "bearish technical should predict loss");
    }

    @Test
    void trainIsNoOpOnEmptyOrMismatchedData() {
        LogisticWinModel model = new LogisticWinModel();
        model.train(new double[0][], new int[0], 100, 0.3, 0.0);
        assertFalse(model.isTrained());
    }

    // ---- CalibrationReporter bucketing ----

    @Test
    void bucketizeGroupsByPredictedDecileWithRealizedWinRate() {
        // Two in the 0.8-0.9 bucket (one win, one loss -> 0.5), one in 0.1-0.2 (loss).
        List<double[]> pairs = List.of(
                new double[]{0.85, 1}, new double[]{0.82, 0}, new double[]{0.15, 0});
        List<CalibrationReporter.Bucket> buckets = CalibrationReporter.bucketize(pairs);
        assertEquals(2, buckets.size());

        CalibrationReporter.Bucket low = buckets.get(0);
        assertEquals("0.1-0.2", low.range());
        assertEquals(1, low.sampleSize());
        assertEquals(0.0, low.realizedWinRate(), EPS);

        CalibrationReporter.Bucket high = buckets.get(1);
        assertEquals("0.8-0.9", high.range());
        assertEquals(2, high.sampleSize());
        assertEquals(0.5, high.realizedWinRate(), EPS);
        assertEquals(0.835, high.avgPredicted(), 1e-6);
    }

    @Test
    void bucketizeClampsProbabilityOfOneIntoTopBucket() {
        List<CalibrationReporter.Bucket> buckets =
                CalibrationReporter.bucketize(List.of(new double[]{1.0, 1}));
        assertEquals(1, buckets.size());
        assertEquals("0.9-1.0", buckets.get(0).range());
    }

    // ---- TechnicalIndicators ----

    @Test
    void computeReturnsNullBelowMinBars() {
        assertNull(TechnicalIndicators.compute(barsOfCloses(closesRange(10))));
    }

    @Test
    void rsiIsHundredWhenEveryBarRises() {
        double[] closes = closesRange(40); // strictly increasing
        assertEquals(100.0, TechnicalIndicators.rsi(closes, 14), 1e-9);
    }

    @Test
    void bollingerPercentBIsHalfAtTheMean() {
        // Flat series -> sd 0 -> guarded to 0.5
        double[] flat = new double[25];
        java.util.Arrays.fill(flat, 100.0);
        assertEquals(0.5, TechnicalIndicators.bollingerPercentB(flat, 20, 2.0), 1e-9);
    }

    @Test
    void momentumIsTenPercentOverTenBars() {
        double[] closes = new double[20];
        for (int i = 0; i < 20; i++) closes[i] = 100.0;
        closes[19] = 110.0; // last is 10% above closes[9]=100
        assertEquals(0.10, TechnicalIndicators.momentum(closes, 10), 1e-9);
    }

    @Test
    void realizedVolIsZeroForConstantPrice() {
        double[] flat = new double[30];
        java.util.Arrays.fill(flat, 50.0);
        assertEquals(0.0, TechnicalIndicators.realizedVolPct(flat), 1e-9);
    }

    @Test
    void computeProducesAllIndicatorsWithEnoughBars() {
        TechnicalIndicators ind = TechnicalIndicators.compute(barsOfCloses(closesRange(50)));
        assertNotNull(ind);
        assertEquals(100.0, ind.rsi14(), 1e-9);          // strictly rising
        assertTrue(ind.macdHistogram() >= 0);            // uptrend -> non-negative histogram
    }

    private static double[] closesRange(int n) {
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = 100.0 + i; // strictly increasing
        return c;
    }

    private static List<CandleBar> barsOfCloses(double[] closes) {
        List<CandleBar> bars = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (double close : closes) {
            bars.add(new CandleBar(t, close, close + 1, close - 1, close, 1000.0));
            t = t.plusSeconds(3600);
        }
        return bars;
    }
}
