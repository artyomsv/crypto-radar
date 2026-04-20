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

    public int countOpenForAccount(Long accountId) {
        return (int) count("exchangeAccountId = ?1 and status in ?2", accountId, OPEN_STATUSES);
    }
}
