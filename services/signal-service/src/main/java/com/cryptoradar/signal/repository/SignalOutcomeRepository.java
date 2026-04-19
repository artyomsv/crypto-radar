package com.cryptoradar.signal.repository;

import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.model.SignalOutcomeId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Panache repository for {@link SignalOutcome}.
 *
 * <p>Deliberately exposes only the queries the tracker, evaluator, and metrics
 * service actually use — keeps the query surface small and indexable.
 */
@ApplicationScoped
public class SignalOutcomeRepository implements PanacheRepositoryBase<SignalOutcome, SignalOutcomeId> {

    /**
     * All outcomes that still need evaluation, oldest first so long-lived
     * trades get checked before fresh ones.
     */
    public List<SignalOutcome> findPending() {
        return find("status = ?1 order by firedAt asc", OutcomeStatus.PENDING).list();
    }

    /**
     * Dedup probe used when a new signal transition or detector fire arrives:
     * returns an open outcome for the same symbol + direction + strategy if
     * one already exists. Dedup is scoped by strategy so that
     * dimension-scoring and trend-continuation can each hold an open outcome
     * on BTC LONG simultaneously — they're different trade ideas and must be
     * measured independently.
     */
    public Optional<SignalOutcome> findOpenByStrategy(String symbol, String direction, String strategy) {
        return find("symbol = ?1 and direction = ?2 and strategy = ?3 and status = ?4",
                symbol, direction, strategy, OutcomeStatus.PENDING)
                .firstResultOptional();
    }

    /**
     * All outcomes fired since a point in time — used by the metrics endpoint.
     */
    public List<SignalOutcome> findFiredSince(Instant since) {
        return find("firedAt >= ?1 order by firedAt asc", since).list();
    }

    /**
     * Most recent outcomes overall, newest first. Used by the trade ledger
     * when no symbol filter is applied.
     */
    public List<SignalOutcome> findRecent(int limit) {
        return find("order by firedAt desc").page(Page.ofSize(limit)).list();
    }

    /**
     * Most recent outcomes for a specific symbol, newest first. Used by the
     * trade ledger (symbol filter) and by the trade chart modal to overlay
     * all trades on a single symbol's candles.
     */
    public List<SignalOutcome> findRecentBySymbol(String symbol, int limit) {
        return find("symbol = ?1 order by firedAt desc", symbol)
                .page(Page.ofSize(limit))
                .list();
    }
}
