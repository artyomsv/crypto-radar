package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the reliability curve — predicted probability vs realized win rate, in
 * deciles — for the stats and LLM estimators. This is the evidence that decides
 * whether a probability is trustworthy enough to promote to a live gate. A
 * well-calibrated estimator's per-bucket win rate tracks the bucket's predicted
 * midpoint; the bucketing math is pure and unit-testable.
 */
@ApplicationScoped
public class CalibrationReporter {

    private static final int BUCKETS = 10;

    private static final String DEFAULT_TAG = "v2-1to1-flip";

    @Inject
    ProbabilityCandidateRepository repository;

    /** One predicted-probability bucket and the realized win rate within it. */
    public record Bucket(String range, int sampleSize, double avgPredicted, double realizedWinRate) {}

    public record Report(String configTag, int totalClosed, double realizedWinRate,
                         List<Bucket> stats, List<Bucket> llm, List<Bucket> calibrated) {}

    public Report report() {
        return report(DEFAULT_TAG);
    }

    @Transactional
    public Report report(String configTag) {
        List<ProbabilityCandidate> closed = repository.findClosedForTag(configTag);
        List<double[]> statsPairs = new ArrayList<>();
        List<double[]> llmPairs = new ArrayList<>();
        List<double[]> calibratedPairs = new ArrayList<>();
        int wins = 0;
        for (ProbabilityCandidate c : closed) {
            int won = ProbabilityCandidate.STATUS_HIT_TARGET.equals(c.status) ? 1 : 0;
            wins += won;
            if (c.statsProb != null) statsPairs.add(new double[]{c.statsProb, won});
            if (c.llmProb != null) llmPairs.add(new double[]{c.llmProb, won});
            if (c.calibratedProb != null) calibratedPairs.add(new double[]{c.calibratedProb, won});
        }
        double realized = closed.isEmpty() ? 0.0 : (double) wins / closed.size();
        return new Report(configTag, closed.size(), realized,
                bucketize(statsPairs), bucketize(llmPairs), bucketize(calibratedPairs));
    }

    /**
     * Groups (predictedProbability, won) pairs into deciles. Pure — no I/O — so
     * the calibration math can be tested directly.
     */
    static List<Bucket> bucketize(List<double[]> pairs) {
        int[] counts = new int[BUCKETS];
        int[] wins = new int[BUCKETS];
        double[] predictedSum = new double[BUCKETS];
        for (double[] pair : pairs) {
            int idx = bucketIndex(pair[0]);
            counts[idx]++;
            wins[idx] += (int) pair[1];
            predictedSum[idx] += pair[0];
        }
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < BUCKETS; i++) {
            if (counts[i] == 0) continue;
            String range = String.format("%.1f-%.1f", i / 10.0, (i + 1) / 10.0);
            out.add(new Bucket(range, counts[i],
                    predictedSum[i] / counts[i],
                    (double) wins[i] / counts[i]));
        }
        return out;
    }

    private static int bucketIndex(double probability) {
        int idx = (int) (probability * BUCKETS);
        if (idx < 0) return 0;
        if (idx >= BUCKETS) return BUCKETS - 1;
        return idx;
    }
}
