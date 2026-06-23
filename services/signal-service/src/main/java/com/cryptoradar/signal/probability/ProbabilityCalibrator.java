package com.cryptoradar.signal.probability;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Empirical recalibration of the LLM probability, scoped per generator config tag:
 * each config's geometry/direction makes its own outcomes the only valid evidence,
 * so a separate bucket map (raw LLM decile → realized win rate) is learned for each
 * enabled generator's tag. Until a bucket has enough samples it falls back to that
 * tag's base rate; until any data exists {@link #calibrate} returns null.
 */
@ApplicationScoped
public class ProbabilityCalibrator {

    private static final Logger LOG = Logger.getLogger(ProbabilityCalibrator.class);
    private static final int BUCKETS = 10;
    private static final int MIN_BUCKET_SAMPLES = 5;

    @Inject ProbabilityCandidateRepository repository;
    @Inject Instance<CandidateGenerator> generators;

    private record Calibration(double[] bucketRate, double baseRate) {}

    private volatile Map<String, Calibration> byTag = Map.of();

    void onStart(@Observes StartupEvent event) {
        retrain();
    }

    @Scheduled(every = "{probability.calibrator.retrain-interval:1h}", delayed = "180s", identity = "prob-calibrator")
    void scheduledRetrain() {
        retrain();
    }

    @Transactional
    public void retrain() {
        try {
            Map<String, Calibration> next = new HashMap<>();
            for (CandidateGenerator generator : generators) {
                Calibration c = learn(generator.tag());
                if (c != null) next.put(generator.tag(), c);
            }
            byTag = next;
        } catch (RuntimeException e) {
            LOG.warnf("Calibrator retrain failed, keeping prior maps: %s", e.getMessage());
        }
    }

    private Calibration learn(String tag) {
        List<ProbabilityCandidate> closed = repository.findClosedWithLlmProbForTag(tag);
        if (closed.isEmpty()) return null;
        int[] counts = new int[BUCKETS];
        int[] wins = new int[BUCKETS];
        int totalWins = 0;
        for (ProbabilityCandidate c : closed) {
            int b = bucketIndex(c.llmProb);
            counts[b]++;
            int won = ProbabilityCandidate.STATUS_HIT_TARGET.equals(c.status) ? 1 : 0;
            wins[b] += won;
            totalWins += won;
        }
        double[] rates = new double[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            rates[i] = counts[i] >= MIN_BUCKET_SAMPLES ? (double) wins[i] / counts[i] : Double.NaN;
        }
        double baseRate = (double) totalWins / closed.size();
        LOG.infof("Calibrator retrained tag=%s on %d closed (base=%.3f)", tag, closed.size(), baseRate);
        return new Calibration(rates, baseRate);
    }

    /** Recalibrated probability for a raw LLM probability under one config tag, or null. */
    public Double calibrate(String tag, Double rawLlmProb) {
        if (rawLlmProb == null) return null;
        Calibration c = byTag.get(tag);
        if (c == null) return null;
        double rate = c.bucketRate()[bucketIndex(rawLlmProb)];
        return Double.isNaN(rate) ? c.baseRate() : rate;
    }

    private static int bucketIndex(double p) {
        int idx = (int) (p * BUCKETS);
        if (idx < 0) return 0;
        if (idx >= BUCKETS) return BUCKETS - 1;
        return idx;
    }
}
