package com.cryptoradar.execution.intake;

import com.cryptoradar.execution.lifecycle.StrategyExitPolicy;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Blocks a breakout-family placement when another OPEN breakout-family trade
 * already holds the same symbol+direction (first-to-fire wins). Existing
 * non-breakout strategies are unaffected. Fail-open: a query error never
 * blocks a trade.
 */
@ApplicationScoped
public class MutualExclusionGuard {

    private static final Logger LOG = Logger.getLogger(MutualExclusionGuard.class);

    private final ExecutedTradeRepository tradeRepo;
    private final StrategyExitPolicy exitPolicy;

    public MutualExclusionGuard(ExecutedTradeRepository tradeRepo, StrategyExitPolicy exitPolicy) {
        this.tradeRepo = tradeRepo;
        this.exitPolicy = exitPolicy;
    }

    /**
     * Returns {@code true} iff an open breakout-family trade already holds
     * {@code symbol}+{@code direction} for this account. Returns {@code false}
     * on any query error (fail-open).
     */
    public boolean isBlocked(Long accountId, String symbol, String direction) {
        try {
            return tradeRepo.findOpenBySymbolAndDirection(accountId, symbol, direction)
                    .map(t -> exitPolicy.isLongHorizon(t.getStrategy()))
                    .orElse(false);
        } catch (RuntimeException e) {
            LOG.warnf(e, "mutual-exclusion query failed for %s %s — failing open", symbol, direction);
            return false;
        }
    }
}
