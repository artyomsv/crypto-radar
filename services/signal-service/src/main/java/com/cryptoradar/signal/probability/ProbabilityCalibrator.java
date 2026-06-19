package com.cryptoradar.signal.probability;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Empirical recalibration of the LLM probability. The shadow data showed the LLM
 * <i>ranks</i> well but its <i>magnitude</i> is inflated (it says 0.62 where the
 * realized rate is ~0.18). This learns a monotone bucket map from closed
 * same-config candidates — raw LLM probability decile → realized win rate — and
 * applies it so the served number means what it says.
 *
 * <p>Scoped to the active {@code probability.config-tag}: when the geometry or
 * direction policy changes, old outcomes no longer describe the new setup, so the
 * calibrator only learns from candidates produced by the current config. Until a
 * bucket has enough samples it falls back to the base rate; until any data exists
 * it returns null (no false precision).
 */
@ApplicationScoped
public class ProbabilityCalibrator {

    private static final Logger LOG = Logger.getLogger(ProbabilityCalibrator.class);
    private static final int BUCKETS = 10;
    private static final int MIN_BUCKET_SAMPLES = 5;

    @Inject
    ProbabilityCandidateRepository repository;

    @ConfigProperty(name = "probability.config-tag", defaultValue = "v2-1to1-flip")
    String configTag;

    private volatile double[] bucketRate;
    private volatile double baseRate = Double.NaN;

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
            List<ProbabilityCandidate> closed = repository.findClosedWithLlmProbForTag(configTag);
            if (closed.isEmpty()) {
                bucketRate = null;
                return;
            }
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
            bucketRate = rates;
            baseRate = (double) totalWins / closed.size();
            LOG.infof("Calibrator retrained on %d closed candidates (tag=%s, base=%.3f)",
                    closed.size(), configTag, baseRate);
        } catch (RuntimeException e) {
            LOG.warnf("Calibrator retrain failed, keeping prior map: %s", e.getMessage());
        }
    }

    /** Recalibrated probability for a raw LLM probability, or null if not yet learnable. */
    public Double calibrate(Double rawLlmProb) {
        if (rawLlmProb == null || bucketRate == null) return null;
        double rate = bucketRate[bucketIndex(rawLlmProb)];
        return Double.isNaN(rate) ? (Double.isNaN(baseRate) ? null : baseRate) : rate;
    }

    private static int bucketIndex(double p) {
        int idx = (int) (p * BUCKETS);
        if (idx < 0) return 0;
        if (idx >= BUCKETS) return BUCKETS - 1;
        return idx;
    }
}
