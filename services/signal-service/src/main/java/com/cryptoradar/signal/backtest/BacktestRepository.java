package com.cryptoradar.signal.backtest;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Data-access layer for backtest runs and their per-trade detail rows.
 */
@ApplicationScoped
public class BacktestRepository implements PanacheRepository<BacktestRun> {

    @Transactional
    public BacktestRun save(BacktestRun run) {
        persist(run);
        return run;
    }

    public Optional<BacktestRun> findRunById(Long id) {
        return findByIdOptional(id);
    }

    /**
     * Most-recent runs for a specific config version, newest first.
     */
    public List<BacktestRun> findByConfigVersion(long configVersionId, int limit) {
        return find("configVersionId = ?1 order by createdAt desc", configVersionId)
                .page(Page.ofSize(limit))
                .list();
    }

    /**
     * Most-recent runs across all config versions, newest first.
     */
    public List<BacktestRun> findRecent(int limit) {
        return find("order by createdAt desc")
                .page(Page.ofSize(limit))
                .list();
    }

    @Transactional
    public void saveTrades(List<BacktestTrade> trades) {
        for (BacktestTrade trade : trades) {
            trade.persist();
        }
    }

    /**
     * All per-trade rows for a given run, ordered by fire time.
     */
    public List<BacktestTrade> findTradesByRun(long runId) {
        return BacktestTrade.find("backtestRunId = ?1 order by outcomeFiredAt asc", runId).list();
    }
}
