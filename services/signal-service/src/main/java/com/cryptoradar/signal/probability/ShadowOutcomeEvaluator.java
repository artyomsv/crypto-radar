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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Forward-evaluates PENDING shadow candidates against real 1h candles to produce
 * the realized label (HIT_TARGET / HIT_STOP / EXPIRED) — the ground truth the
 * calibration report compares predictions against. No orders, no live state;
 * this only walks price history that actually happened.
 *
 * <p>When a single bar straddles both stop and target, the stop is assumed hit
 * first (conservative — the same assumption the live evaluator makes for an
 * un-trailed position).
 *
 * <p><b>No long transaction around HTTP.</b> The scheduled tick is NOT
 * transactional: it takes a short read snapshot, fetches candles once per symbol
 * outside any transaction, then persists each close in its own short transaction.
 * The earlier "one @Transactional method that fetched candles for every pending
 * candidate" blew the 60s transaction timeout as the backlog grew and rolled the
 * whole batch back — freezing progress. Per-symbol fetch + per-close commit keeps
 * every unit of work small and independent.
 */
@ApplicationScoped
public class ShadowOutcomeEvaluator {

    private static final Logger LOG = Logger.getLogger(ShadowOutcomeEvaluator.class);
    private static final String CANDLE_INTERVAL = "1h";
    private static final int HOLD_HOURS = 72;
    private static final int CANDLE_LIMIT = HOLD_HOURS + 24;

    @Inject CandleClient candleClient;
    @Inject ProbabilityCandidateRepository repository;

    /** Detached snapshot of a pending candidate — no entity crosses the HTTP boundary. */
    private record Pending(Long id, String symbol, String direction, Instant scannedAt,
                           double stop, double target, double entry) {}

    @Scheduled(every = "{probability.eval.interval:15m}", delayed = "150s", identity = "probability-eval")
    void evaluate() {
        List<Pending> pending = loadPending();
        if (pending.isEmpty()) return;
        Map<String, List<Pending>> bySymbol = pending.stream().collect(Collectors.groupingBy(Pending::symbol));
        int closed = 0;
        for (Map.Entry<String, List<Pending>> entry : bySymbol.entrySet()) {
            List<CandleBar> bars;
            try {
                bars = candleClient.fetchRecent(entry.getKey(), CANDLE_INTERVAL, CANDLE_LIMIT);
            } catch (RuntimeException e) {
                LOG.warnf("Candle fetch failed for %s — skipping its candidates: %s", entry.getKey(), e.getMessage());
                continue;
            }
            for (Pending p : entry.getValue()) {
                try {
                    if (closeIfResolved(p, bars)) closed++;
                } catch (RuntimeException e) {
                    LOG.warnf("Shadow eval failed for candidate %d (%s): %s", p.id(), p.symbol(), e.getMessage());
                }
            }
        }
        if (closed > 0) LOG.infof("Shadow evaluator closed %d/%d candidates", closed, pending.size());
    }

    /** Short read transaction: snapshot pending candidates into detached records. */
    @Transactional
    List<Pending> loadPending() {
        return repository.findPending().stream()
                .map(c -> new Pending(c.id, c.symbol, c.direction, c.scannedAt,
                        c.stopPrice, c.targetPrice, c.entryPrice))
                .toList();
    }

    private boolean closeIfResolved(Pending p, List<CandleBar> bars) {
        boolean isLong = Candidate.LONG.equals(p.direction());
        for (CandleBar bar : bars) {
            if (!bar.time().isAfter(p.scannedAt())) continue;
            if (isLong) {
                if (bar.low() <= p.stop()) return close(p.id(), ProbabilityCandidate.STATUS_HIT_STOP, p.stop(), bar.time());
                if (bar.high() >= p.target()) return close(p.id(), ProbabilityCandidate.STATUS_HIT_TARGET, p.target(), bar.time());
            } else {
                if (bar.high() >= p.stop()) return close(p.id(), ProbabilityCandidate.STATUS_HIT_STOP, p.stop(), bar.time());
                if (bar.low() <= p.target()) return close(p.id(), ProbabilityCandidate.STATUS_HIT_TARGET, p.target(), bar.time());
            }
        }
        if (Duration.between(p.scannedAt(), Instant.now()).toHours() >= HOLD_HOURS) {
            double lastClose = bars.isEmpty() ? p.entry() : bars.get(bars.size() - 1).close();
            return close(p.id(), ProbabilityCandidate.STATUS_EXPIRED, lastClose, Instant.now());
        }
        return false;
    }

    /** Short write transaction per close — independent commit, no batch rollback. */
    @Transactional
    boolean close(Long id, String status, double price, Instant when) {
        ProbabilityCandidate c = repository.findById(id);
        if (c == null || !ProbabilityCandidate.STATUS_PENDING.equals(c.status)) return false;
        c.status = status;
        c.closedPrice = price;
        c.closedAt = when;
        repository.persist(c);
        return true;
    }
}
