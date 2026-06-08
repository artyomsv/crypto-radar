package com.cryptoradar.options.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    // 5-minute cache. Annualized RV moves on the scale of hours; the enriched
    // endpoint can be hit dozens of times/minute. Caching cuts the dominant
    // per-call cost (Hibernate native-query setup + TimescaleDB chunk planning)
    // from 400–800ms to a HashMap lookup.
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private final ConcurrentHashMap<String, CachedRv> cache = new ConcurrentHashMap<>();

    /**
     * Underlying naming convention: Bybit options use bare ticker
     * ("BTC", "ETH"); spot candles use USDT-quote pair ("BTCUSDT").
     * Map here so callers can pass the underlying form.
     */
    public Double computeAnnualized(String underlying, int lookbackDays) {
        String key = underlying + "/" + lookbackDays;
        CachedRv cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
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
        Double result = dailyStdev * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100.0;
        cache.put(key, new CachedRv(result, Instant.now()));
        return result;
    }

    private record CachedRv(Double value, Instant cachedAt) {
        boolean isExpired() {
            return Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) >= 0;
        }
    }

    @SuppressWarnings("unchecked")
    @Transactional
    List<Double> fetchDailyCloses(String spotSymbol, int limit) {
        try {
            // The {@code time > now() - 90 days} predicate lets the TimescaleDB
            // planner prune chunks aggressively. Without it the planner appends
            // every 1d-interval chunk in the hypertable (hundreds of them);
            // observed planning cost was 400-800ms per call even though the
            // actual scan was sub-ms.
            List<Object> results = entityManager.createNativeQuery("""
                SELECT close FROM candles
                WHERE symbol = :symbol AND interval = '1d'
                  AND time > now() - interval '90 days'
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

    /**
     * Where the most recent ATM IV sits in its own 30-day rolling distribution
     * (0–100 percentile). Used by the short-vol scorer's {@code ivPercentile}
     * component — Sinclair Ch. 7: only sell vol when current vol is in an
     * elevated percentile of its own history. Returns {@code null} when there
     * are not enough historical snapshots to score.
     *
     * <p>Cheap to compute: one query, then in-memory rank. Cached at the
     * same 5-min TTL as {@link #computeAnnualized}.
     */
    public Double computeIvPercentileLast30d(String underlying) {
        String key = underlying + "/iv-pct-30d";
        CachedRv cached = cache.get(key);
        if (cached != null && !cached.isExpired()) return cached.value;
        Double result = fetchIvPercentileLast30d(underlying);
        cache.put(key, new CachedRv(result, Instant.now()));
        return result;
    }

    @SuppressWarnings("unchecked")
    @Transactional
    Double fetchIvPercentileLast30d(String underlying) {
        try {
            // Take one ATM IV reading per day for the last 30 days, then percentile-rank.
            List<Object> rows = entityManager.createNativeQuery("""
                SELECT AVG(implied_vol) AS daily_iv
                FROM option_snapshots
                WHERE underlying = :u
                  AND time > now() - interval '30 days'
                  AND implied_vol IS NOT NULL
                GROUP BY date_trunc('day', time)
                ORDER BY date_trunc('day', time)
                """)
                    .setParameter("u", underlying)
                    .getResultList();
            if (rows.size() < 7) return null;   // not enough history to percentile
            double[] series = rows.stream().mapToDouble(o -> ((Number) o).doubleValue()).toArray();
            double current = series[series.length - 1];
            long below = 0;
            for (double v : series) if (v < current) below++;
            return 100.0 * below / series.length;
        } catch (Exception e) {
            LOG.warnf(e, "IV percentile query failed for %s", underlying);
            return null;
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
