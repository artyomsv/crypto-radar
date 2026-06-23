package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.TradingSignal;
import com.cryptoradar.signal.service.CandleClient;
import com.cryptoradar.signal.service.SignalService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Trains {@link DirectionModel} from real historical 1h candles. For each tracked
 * symbol it slides a window: at bar t it computes indicators from bars ≤ t and the
 * label from bars > t (did a LONG 1:1 trade hit target before stop within the hold
 * window). Strict look-ahead discipline — features never read bars after t.
 *
 * <p>Real data only: candles are real observations and labels come from real
 * forward price. No fabricated rows. Fail-open: any error keeps the prior fit.
 */
@ApplicationScoped
public class DirectionModelTrainer {

    private static final Logger LOG = Logger.getLogger(DirectionModelTrainer.class);
    private static final String INTERVAL = "1h";
    private static final int ATR_PERIOD = 14;
    private static final int MIN_FEATURE_BARS = 35; // TechnicalIndicators MIN_BARS
    private static final int EPOCHS = 4000;
    private static final double LEARNING_RATE = 0.3;
    private static final double L2 = 0.001;

    @Inject CandleClient candleClient;
    @Inject SignalService signalService;

    @ConfigProperty(name = "probability.direction-model.lookback-days", defaultValue = "60")
    int lookbackDays;
    @ConfigProperty(name = "probability.direction-model.hold-hours", defaultValue = "72")
    int holdHours;

    private final DirectionModel model = new DirectionModel();

    public DirectionModel model() {
        return model;
    }

    void onStart(@Observes StartupEvent event) {
        retrain();
    }

    @Scheduled(every = "{probability.direction-model.retrain-interval:6h}", delayed = "210s",
            identity = "direction-model-retrain")
    void scheduledRetrain() {
        retrain();
    }

    void retrain() {
        try {
            List<double[]> rows = new ArrayList<>();
            List<Integer> labels = new ArrayList<>();
            for (TradingSignal signal : signalService.getSignalOverview().getSignals()) {
                try {
                    accumulate(signal.getSymbol(), rows, labels);
                } catch (RuntimeException e) {
                    LOG.warnf("Direction-model training skipped %s: %s", signal.getSymbol(), e.getMessage());
                }
            }
            if (rows.isEmpty()) {
                LOG.warn("Direction-model retrain skipped — no training rows");
                return;
            }
            double[][] x = rows.toArray(new double[0][]);
            int[] y = labels.stream().mapToInt(Integer::intValue).toArray();
            model.train(x, y, EPOCHS, LEARNING_RATE, L2);
            LOG.infof("Direction model trained on %d rows (%d long-wins)",
                    y.length, java.util.Arrays.stream(y).sum());
        } catch (RuntimeException e) {
            LOG.warnf("Direction-model retrain failed, keeping prior fit: %s", e.getMessage());
        }
    }

    private void accumulate(String symbol, List<double[]> rows, List<Integer> labels) {
        List<CandleBar> bars = candleClient.fetchRecent(symbol, INTERVAL, lookbackDays * 24);
        int lastEntryIdx = bars.size() - holdHours - 1;
        for (int t = MIN_FEATURE_BARS - 1; t <= lastEntryIdx; t++) {
            List<CandleBar> window = bars.subList(0, t + 1);
            TechnicalIndicators ind = TechnicalIndicators.compute(window);
            if (ind == null) continue;
            double atr = AtrCalculator.atr(window, ATR_PERIOD);
            double entry = bars.get(t).close();
            if (atr <= 0 || entry <= 0) continue;
            Candidate c = CandidateBuilder.build(Candidate.LONG, entry, atr, 1.5, 1.0);
            List<CandleBar> forward = bars.subList(t + 1, Math.min(t + 1 + holdHours, bars.size()));
            String status = LabelWalker.resolve(forward, entry, c.stop(), c.target(), true);
            rows.add(DirectionModel.toVector(ind));
            labels.add(ProbabilityCandidate.STATUS_HIT_TARGET.equals(status) ? 1 : 0);
        }
    }
}
