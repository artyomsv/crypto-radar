package com.cryptoradar.execution.repository;

import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ExecutedTradeRepository implements PanacheRepository<ExecutedTrade> {

    private static final List<TradeStatus> OPEN_STATUSES =
            List.of(TradeStatus.PENDING_PLACE, TradeStatus.OPEN, TradeStatus.CLOSING);

    public List<ExecutedTrade> findOpenForAccount(Long accountId) {
        return find("exchangeAccountId = ?1 and status in ?2",
                Sort.descending("openedAt"), accountId, OPEN_STATUSES).list();
    }

    public Optional<ExecutedTrade> findOpenBySymbolAndDirectionAndStrategy(
            Long accountId, String symbol, String direction, String strategy) {
        return find(
                "exchangeAccountId = ?1 and symbol = ?2 and direction = ?3 and strategy = ?4 and status in ?5",
                accountId, symbol, direction, strategy, OPEN_STATUSES)
                .firstResultOptional();
    }

    public Optional<ExecutedTrade> findByOrderLinkId(String orderLinkId) {
        return find("exchangeOrderLinkId = ?1", orderLinkId).firstResultOptional();
    }

    public List<ExecutedTrade> findClosedSince(Long accountId, Instant since, int limit) {
        return find("exchangeAccountId = ?1 and status = ?2 and closedAt >= ?3",
                Sort.descending("closedAt"), accountId, TradeStatus.CLOSED, since)
                .page(0, limit).list();
    }

    public List<ExecutedTrade> findClosedPaged(Long accountId, int page, int pageSize) {
        return find("exchangeAccountId = ?1 and status = ?2",
                Sort.descending("closedAt"), accountId, TradeStatus.CLOSED)
                .page(page, pageSize).list();
    }

    public long countClosedForAccount(Long accountId) {
        return count("exchangeAccountId = ?1 and status = ?2", accountId, TradeStatus.CLOSED);
    }

    /**
     * Closed rows missing any of: realized_pnl_usdt, exit_reason, entry_price.
     * Used by the admin backfill to recover metadata for trades that were
     * marked CLOSED before {@link com.cryptoradar.execution.lifecycle.OrderReconciler}
     * could fetch the closed-pnl payload from Bybit.
     */
    public List<ExecutedTrade> findIncompleteCloses(Long accountId, int limit) {
        return find(
                "exchangeAccountId = ?1 and status = ?2 "
                + "and (realizedPnlUsdt is null or exitReason is null or entryPrice is null)",
                Sort.descending("closedAt"), accountId, TradeStatus.CLOSED)
                .page(0, limit).list();
    }

    /**
     * Closed rows whose recorded exit reason contradicts the PnL sign — caused
     * by the legacy reconciler bug that silently defaulted exit_reason to
     * TARGET for any externally-closed trade. Pure local re-classification;
     * no Bybit call needed.
     */
    public List<ExecutedTrade> findMislabeledExits(Long accountId, int limit) {
        // Includes MANUAL — under the new inference logic MANUAL is reserved
        // for genuinely ambiguous closes (no PnL, no levels), so any row
        // marked MANUAL with usable data can be reclassified.
        return find(
                "exchangeAccountId = ?1 and status = ?2 and ("
                + "(exitReason = ?3 and realizedPnlUsdt <= 0) "
                + "or (exitReason = ?4 and realizedPnlUsdt > 0) "
                + "or exitReason = ?5)",
                Sort.descending("closedAt"), accountId, TradeStatus.CLOSED,
                com.cryptoradar.execution.model.ExitReason.TARGET,
                com.cryptoradar.execution.model.ExitReason.INITIAL_STOP,
                com.cryptoradar.execution.model.ExitReason.MANUAL)
                .page(0, limit).list();
    }

    public int countOpenForAccount(Long accountId) {
        return (int) count("exchangeAccountId = ?1 and status in ?2", accountId, OPEN_STATUSES);
    }

    /**
     * Sum of realized PnL for the account's trades closed since {@code since}.
     * Used by the daily-loss gate to compute today's PnL window.
     * Returns zero when no rows exist or the sum is null.
     */
    public java.math.BigDecimal sumRealizedPnlSince(Long accountId, Instant since) {
        Object result = getEntityManager().createQuery(
                        "SELECT COALESCE(SUM(t.realizedPnlUsdt), 0) FROM ExecutedTrade t "
                        + "WHERE t.exchangeAccountId = :accountId "
                        + "AND t.status = :status "
                        + "AND t.closedAt >= :since")
                .setParameter("accountId", accountId)
                .setParameter("status", TradeStatus.CLOSED)
                .setParameter("since", since)
                .getSingleResult();
        if (result instanceof java.math.BigDecimal bd) return bd;
        if (result instanceof Number n) return java.math.BigDecimal.valueOf(n.doubleValue());
        return java.math.BigDecimal.ZERO;
    }
}
