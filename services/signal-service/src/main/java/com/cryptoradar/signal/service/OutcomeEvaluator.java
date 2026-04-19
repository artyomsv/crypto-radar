package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Every minute, walks forward through the latest 1m candles of each PENDING
 * signal and checks whether the trade hit stop, target, or should be expired.
 *
 * <p>This is the other half of the feedback loop: {@code OutcomeTracker}
 * records what the engine predicted, and this class records what actually
 * happened. Together they enable after-the-fact measurement of signal quality.
 */
@ApplicationScoped
public class OutcomeEvaluator {

    private static final Logger LOG = Logger.getLogger(OutcomeEvaluator.class);

    private static final String INTERVAL_1M = "1m";
    private static final int CANDLE_FETCH_LIMIT = 1500;
    private static final Duration MAX_HOLD = Duration.ofDays(7);
    private static final String DIRECTION_LONG = "LONG";

    private final SignalOutcomeRepository repository;
    private final CandleClient candleClient;

    public OutcomeEvaluator(SignalOutcomeRepository repository, CandleClient candleClient) {
        this.repository = repository;
        this.candleClient = candleClient;
    }

    @Scheduled(every = "60s", delayed = "30s")
    @Transactional
    public void evaluatePending() {
        List<SignalOutcome> pending = repository.findPending();
        if (pending.isEmpty()) return;

        int closed = 0;
        for (SignalOutcome outcome : pending) {
            boolean didClose = evaluateOne(outcome);
            if (didClose) closed++;
        }
        LOG.debugf("Evaluated %d pending outcomes, closed %d", pending.size(), closed);
    }

    /**
     * Evaluates a single pending outcome against the latest 1m candles.
     * Returns {@code true} if the outcome transitioned to a terminal state.
     */
    private boolean evaluateOne(SignalOutcome outcome) {
        List<CandleBar> bars = candleClient.fetchRecent(outcome.getSymbol(), INTERVAL_1M, CANDLE_FETCH_LIMIT);
        if (bars.isEmpty()) return false;

        Instant scanAfter = resolveScanStart(outcome);
        for (CandleBar bar : bars) {
            if (!bar.time().isAfter(scanAfter)) continue;
            updateExcursions(outcome, bar);
            OutcomeStatus hit = detectHit(outcome, bar);
            if (hit != null) {
                closeOutcome(outcome, hit, bar.time(), priceFor(outcome, hit));
                return true;
            }
        }

        outcome.setLastEvaluatedAt(bars.get(bars.size() - 1).time());
        return expireIfStale(outcome, bars);
    }

    private Instant resolveScanStart(SignalOutcome outcome) {
        Instant last = outcome.getLastEvaluatedAt();
        return last != null ? last : outcome.getFiredAt();
    }

    private OutcomeStatus detectHit(SignalOutcome outcome, CandleBar bar) {
        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
        boolean stopHit = isLong
                ? bar.low() <= outcome.getStopPrice()
                : bar.high() >= outcome.getStopPrice();
        boolean targetHit = isLong
                ? bar.high() >= outcome.getTargetPrice()
                : bar.low() <= outcome.getTargetPrice();

        // If both levels are inside a single 1m bar we can't know order without
        // tick data — assume stop first as the pessimistic convention.
        if (stopHit) return OutcomeStatus.HIT_STOP;
        if (targetHit) return OutcomeStatus.HIT_TARGET;
        return null;
    }

    private double priceFor(SignalOutcome outcome, OutcomeStatus terminal) {
        return terminal == OutcomeStatus.HIT_TARGET
                ? outcome.getTargetPrice()
                : outcome.getStopPrice();
    }

    private void updateExcursions(SignalOutcome outcome, CandleBar bar) {
        double entry = outcome.getEntryPrice();
        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());

        double bestPrice = isLong ? bar.high() : bar.low();
        double worstPrice = isLong ? bar.low() : bar.high();

        double mfeCandidate = pctMove(entry, bestPrice, isLong);
        double maeCandidate = pctMove(entry, worstPrice, isLong);

        if (mfeCandidate > outcome.getMaxFavorablePct()) {
            outcome.setMaxFavorablePct(mfeCandidate);
        }
        if (maeCandidate < outcome.getMaxAdversePct()) {
            outcome.setMaxAdversePct(maeCandidate);
        }
    }

    private double pctMove(double entry, double price, boolean isLong) {
        double raw = (price - entry) / entry * 100.0;
        return isLong ? raw : -raw;
    }

    private boolean expireIfStale(SignalOutcome outcome, List<CandleBar> bars) {
        Duration age = Duration.between(outcome.getFiredAt(), Instant.now());
        if (age.compareTo(MAX_HOLD) < 0) return false;

        CandleBar last = bars.get(bars.size() - 1);
        closeOutcome(outcome, OutcomeStatus.EXPIRED, last.time(), last.close());
        return true;
    }

    private void closeOutcome(SignalOutcome outcome, OutcomeStatus status,
                              Instant closedAt, double closedPrice) {
        outcome.setStatus(status);
        outcome.setClosedAt(closedAt);
        outcome.setClosedPrice(closedPrice);
        outcome.setLastEvaluatedAt(closedAt);

        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
        double pnlPct = pctMove(outcome.getEntryPrice(), closedPrice, isLong);
        double risk = Math.abs(outcome.getEntryPrice() - outcome.getStopPrice());
        double rMultiple = risk > 0
                ? (closedPrice - outcome.getEntryPrice()) * (isLong ? 1 : -1) / risk
                : 0.0;

        outcome.setRealizedPnlPct(pnlPct);
        outcome.setRealizedRMultiple(rMultiple);

        LOG.infof("CLOSE %s %s %s @ %.4f  pnl=%.2f%%  R=%.2f",
                outcome.getSymbol(), outcome.getDirection(), status, closedPrice, pnlPct, rMultiple);
    }
}
