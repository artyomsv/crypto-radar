package com.cryptoradar.options.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Computes annualized realized volatility from the shared {@code candles}
 * hypertable (populated by market-data-service). Cross-service read uses the
 * same {@code EntityManager.createNativeQuery} pattern as
 * {@code SymbolPerformanceGate} in trade-execution-service.
 *
 * <p>Formula: {@code RV = stdev(ln(close_t / close_{t-1})) * sqrt(365) * 100}.
 * Output in percent (e.g. 65.4 = 65.4% annualized vol).
 */
@ApplicationScoped
public class RealizedVolService {

    private static final Logger LOG = Logger.getLogger(RealizedVolService.class);
    private static final double TRADING_DAYS_PER_YEAR = 365.0;

    @Inject EntityManager entityManager;

    /**
     * Underlying naming convention: Bybit options use bare ticker
     * ("BTC", "ETH"); spot candles use USDT-quote pair ("BTCUSDT").
     * Map here so callers can pass the underlying form.
     */
    public Double computeAnnualized(String underlying, int lookbackDays) {
        String spotSymbol = underlying + "USDT";
        List<Double> closes = fetchDailyCloses(spotSymbol, lookbackDays + 1);
        if (closes.size() < 3) return null;
        double[] logReturns = new double[closes.size() - 1];
        for (int i = 1; i < closes.size(); i++) {
            double prev = closes.get(i - 1);
            double curr = closes.get(i);
            if (prev <= 0 || curr <= 0) return null;
            logReturns[i - 1] = Math.log(curr / prev);
        }
        double mean = 0.0;
        for (double r : logReturns) mean += r;
        mean /= logReturns.length;
        double variance = 0.0;
        for (double r : logReturns) variance += (r - mean) * (r - mean);
        variance /= (logReturns.length - 1);
        double dailyStdev = Math.sqrt(variance);
        return dailyStdev * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100.0;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    List<Double> fetchDailyCloses(String spotSymbol, int limit) {
        try {
            List<Object> results = entityManager.createNativeQuery("""
                SELECT close FROM candles
                WHERE symbol = :symbol AND interval = '1d'
                ORDER BY time DESC
                LIMIT :limit
                """)
                    .setParameter("symbol", spotSymbol)
                    .setParameter("limit", limit)
                    .getResultList();
            // Reverse to chronological order for log-return calculation.
            return results.stream()
                    .map(o -> ((Number) o).doubleValue())
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toList(),
                            list -> { java.util.Collections.reverse(list); return list; }));
        } catch (Exception e) {
            LOG.warnf(e, "RealizedVolService query failed for %s", spotSymbol);
            return List.of();
        }
    }

    /** Bybit's published HV (most recent reading per period). */
    @SuppressWarnings("unchecked")
    @Transactional
    public Double latestBybitHv(String underlying, int periodDays) {
        try {
            Object result = entityManager.createNativeQuery("""
                SELECT hv FROM option_historical_vol
                WHERE underlying = :u AND period_days = :p
                ORDER BY time DESC LIMIT 1
                """)
                    .setParameter("u", underlying)
                    .setParameter("p", periodDays)
                    .getSingleResult();
            if (result instanceof Number n) return n.doubleValue();
            if (result instanceof BigDecimal bd) return bd.doubleValue();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        } catch (Exception e) {
            LOG.warnf(e, "latestBybitHv failed for %s/%d", underlying, periodDays);
        }
        return null;
    }
}
