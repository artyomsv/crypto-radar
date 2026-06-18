package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the hybrid win-probability estimate.
 *
 * <p>Stats half: trains a {@link LogisticWinModel} in-process from closed
 * {@code signal_outcomes} (the only features with historical labels — the six
 * dimension scores) at startup and on a schedule. The label is "did this setup
 * hit its target before its stop" ({@code HIT_TARGET}), exactly the event we
 * predict. LLM half: delegates to {@link GeminiProbabilityClient} for a
 * reasoning overlay. The two are returned independently — the blend is a Phase 2
 * decision made from calibration evidence, not guessed here.
 */
@ApplicationScoped
public class WinProbabilityEstimator {

    private static final Logger LOG = Logger.getLogger(WinProbabilityEstimator.class);

    // Feature order must match LogisticWinModel and the signal_outcomes columns.
    private static final String[] DIMENSIONS =
            {"Technical", "Whale", "Derivatives", "Sentiment", "Order Book", "Macro"};

    private static final int EPOCHS = 400;
    private static final double LEARNING_RATE = 0.3;
    private static final double L2 = 0.001;

    private final LogisticWinModel model = new LogisticWinModel();

    @Inject
    SignalOutcomeRepository outcomeRepo;

    @Inject
    GeminiProbabilityClient geminiClient;

    void onStart(@Observes StartupEvent event) {
        retrain();
    }

    @Scheduled(every = "{probability.model.retrain-interval:6h}", delayed = "120s", identity = "winmodel-retrain")
    void scheduledRetrain() {
        retrain();
    }

    /** Re-fits the logistic model on all closed outcomes. Fail-open: keeps prior fit on error. */
    @Transactional
    public void retrain() {
        try {
            List<SignalOutcome> closed = outcomeRepo.list("status <> ?1", OutcomeStatus.PENDING);
            List<double[]> rows = new java.util.ArrayList<>();
            List<Integer> labels = new java.util.ArrayList<>();
            for (SignalOutcome o : closed) {
                double[] row = featureRow(o);
                if (row == null) continue;
                rows.add(row);
                labels.add(o.getStatus() == OutcomeStatus.HIT_TARGET ? 1 : 0);
            }
            if (rows.isEmpty()) {
                LOG.warn("Win model retrain skipped — no labeled outcomes yet");
                return;
            }
            double[][] x = rows.toArray(new double[0][]);
            int[] y = labels.stream().mapToInt(Integer::intValue).toArray();
            model.train(x, y, EPOCHS, LEARNING_RATE, L2);
            LOG.infof("Win model retrained on %d closed outcomes (%d wins)",
                    y.length, java.util.Arrays.stream(y).sum());
        } catch (RuntimeException e) {
            LOG.warnf("Win model retrain failed, keeping prior fit: %s", e.getMessage());
        }
    }

    public boolean isTrained() {
        return model.isTrained();
    }

    /** Calibrated stats probability for a dimension-score map (scan time). */
    public double statsProbability(Map<String, Double> dimensionScores) {
        return model.predict(featureRow(dimensionScores));
    }

    public Optional<GeminiProbabilityClient.LlmEstimate> llmProbability(String prompt) {
        return geminiClient.estimate(prompt);
    }

    /** Feature row from a closed outcome's six stored scores; null if all are absent. */
    private double[] featureRow(SignalOutcome o) {
        Double[] scores = {o.getTechnicalScore(), o.getWhaleScore(), o.getDerivativesScore(),
                o.getSentimentScore(), o.getOrderbookScore(), o.getMacroScore()};
        boolean anyPresent = false;
        double[] row = new double[DIMENSIONS.length];
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] != null) anyPresent = true;
            row[i] = scores[i] == null ? 0.0 : scores[i];
        }
        return anyPresent ? row : null;
    }

    /** Feature row from a live dimension-score map, in the canonical order. */
    private double[] featureRow(Map<String, Double> dimensionScores) {
        double[] row = new double[DIMENSIONS.length];
        for (int i = 0; i < DIMENSIONS.length; i++) {
            Double v = dimensionScores.get(DIMENSIONS[i]);
            row[i] = v == null ? 0.0 : v;
        }
        return row;
    }
}
