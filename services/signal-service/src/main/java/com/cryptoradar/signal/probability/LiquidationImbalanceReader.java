package com.cryptoradar.signal.probability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Reads recent multi-venue liquidation pressure for a symbol from the shared
 * {@code liquidations} table (now normalized to LONG/SHORT across Binance/OKX/
 * Bybit). Returns a signed imbalance in [-1, 1]:
 * {@code (longLiqUsd - shortLiqUsd) / (longLiqUsd + shortLiqUsd)} — positive when
 * more longs were liquidated (downside flush), negative for short squeezes.
 *
 * <p>A genuinely new feature the dimension scores don't capture. Fail-open:
 * returns null on any error or when there is no recent liquidation data.
 */
@ApplicationScoped
public class LiquidationImbalanceReader {

    private static final Logger LOG = Logger.getLogger(LiquidationImbalanceReader.class);
    private static final int LOOKBACK_HOURS = 24;

    @Inject
    EntityManager entityManager;

    @Transactional
    public Double imbalance24h(String symbol) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT side, COALESCE(SUM(value_usd), 0) FROM liquidations " +
                    "WHERE symbol = :symbol AND time > now() - make_interval(hours => :hrs) " +
                    "GROUP BY side")
                    .setParameter("symbol", symbol)
                    .setParameter("hrs", LOOKBACK_HOURS)
                    .getResultList();
            double longUsd = 0, shortUsd = 0;
            for (Object[] row : rows) {
                String side = String.valueOf(row[0]);
                double total = ((Number) row[1]).doubleValue();
                if ("LONG".equals(side)) longUsd = total;
                else if ("SHORT".equals(side)) shortUsd = total;
            }
            double denom = longUsd + shortUsd;
            if (denom <= 0) return null;
            return (longUsd - shortUsd) / denom;
        } catch (RuntimeException e) {
            LOG.debugf("Liquidation imbalance query failed for %s: %s", symbol, e.getMessage());
            return null;
        }
    }
}
