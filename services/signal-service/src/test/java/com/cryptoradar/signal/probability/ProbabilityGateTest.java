package com.cryptoradar.signal.probability;

import org.junit.jupiter.api.Test;

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
        Candidate c = CandidateBuilder.build(Candidate.LONG, 100.0, 4.0); // risk = 1.5*4 = 6
        assertEquals(94.0, c.stop(), EPS);
        assertEquals(112.0, c.target(), EPS);   // entry + 2*risk
        assertEquals(2.0, c.riskReward(), EPS);
        assertTrue(c.isLong());
    }

    @Test
    void shortCandidateMirrorsGeometry() {
        Candidate c = CandidateBuilder.build(Candidate.SHORT, 100.0, 4.0); // risk = 6
        assertEquals(106.0, c.stop(), EPS);
        assertEquals(88.0, c.target(), EPS);
        assertEquals(2.0, c.riskReward(), EPS);
    }

    @Test
    void riskIsFlooredAtMinRiskPctWhenAtrIsTiny() {
        // 1.5*0.1 = 0.15 < MIN_RISK_PCT*100 = 1.5 -> risk floored to 1.5
        Candidate c = CandidateBuilder.build(Candidate.LONG, 100.0, 0.1);
        assertEquals(98.5, c.stop(), EPS);
        assertEquals(103.0, c.target(), EPS);
    }

    @Test
    void rejectsNonPositiveInputsAndBadDirection() {
        assertThrows(IllegalArgumentException.class, () -> CandidateBuilder.build(Candidate.LONG, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CandidateBuilder.build(Candidate.LONG, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> CandidateBuilder.build("SIDEWAYS", 100, 1));
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
}
