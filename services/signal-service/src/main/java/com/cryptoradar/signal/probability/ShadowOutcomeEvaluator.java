package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.service.CandleClient;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Forward-evaluates PENDING shadow candidates against real 1h candles to produce
 * the realized label (HIT_TARGET / HIT_STOP / EXPIRED) — the ground truth the
 * calibration report compares predictions against. No orders, no live state;
 * this only walks price history that actually happened.
 *
 * <p>When a single bar straddles both stop and target, the stop is assumed hit
 * first (conservative — the same assumption the live evaluator makes for an
 * un-trailed position).
 */
@ApplicationScoped
public class ShadowOutcomeEvaluator {

    private static final Logger LOG = Logger.getLogger(ShadowOutcomeEvaluator.class);
    private static final String CANDLE_INTERVAL = "1h";
    private static final int HOLD_HOURS = 72;
    private static final int CANDLE_LIMIT = HOLD_HOURS + 24;

    @Inject CandleClient candleClient;
    @Inject ProbabilityCandidateRepository repository;

    @Scheduled(every = "{probability.eval.interval:15m}", delayed = "150s", identity = "probability-eval")
    @Transactional
    void evaluate() {
        List<ProbabilityCandidate> pending = repository.findPending();
        int closed = 0;
        for (ProbabilityCandidate c : pending) {
            try {
                if (evaluateOne(c)) closed++;
            } catch (RuntimeException e) {
                LOG.warnf("Shadow eval failed for candidate %d (%s): %s", c.id, c.symbol, e.getMessage());
            }
        }
        if (closed > 0) LOG.infof("Shadow evaluator closed %d/%d candidates", closed, pending.size());
    }

    private boolean evaluateOne(ProbabilityCandidate c) {
        List<CandleBar> bars = candleClient.fetchRecent(c.symbol, CANDLE_INTERVAL, CANDLE_LIMIT);
        boolean isLong = Candidate.LONG.equals(c.direction);
        for (CandleBar bar : bars) {
            if (!bar.time().isAfter(c.scannedAt)) continue;
            if (isLong) {
                if (bar.low() <= c.stopPrice) return close(c, ProbabilityCandidate.STATUS_HIT_STOP, c.stopPrice, bar.time());
                if (bar.high() >= c.targetPrice) return close(c, ProbabilityCandidate.STATUS_HIT_TARGET, c.targetPrice, bar.time());
            } else {
                if (bar.high() >= c.stopPrice) return close(c, ProbabilityCandidate.STATUS_HIT_STOP, c.stopPrice, bar.time());
                if (bar.low() <= c.targetPrice) return close(c, ProbabilityCandidate.STATUS_HIT_TARGET, c.targetPrice, bar.time());
            }
        }
        // Neither level hit — expire once past the hold window, else leave pending.
        if (Duration.between(c.scannedAt, Instant.now()).toHours() >= HOLD_HOURS) {
            double lastClose = bars.isEmpty() ? c.entryPrice : bars.get(bars.size() - 1).close();
            return close(c, ProbabilityCandidate.STATUS_EXPIRED, lastClose, Instant.now());
        }
        return false;
    }

    private boolean close(ProbabilityCandidate c, String status, double price, Instant when) {
        c.status = status;
        c.closedPrice = price;
        c.closedAt = when;
        repository.persist(c);
        return true;
    }
}
