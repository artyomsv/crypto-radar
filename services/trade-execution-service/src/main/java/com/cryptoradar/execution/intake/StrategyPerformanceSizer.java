package com.cryptoradar.execution.intake;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-cell (symbol × direction × strategy) position-size multiplier.
 *
 * <p>The {@link SymbolPerformanceGate} suppresses dispatch when a symbol's
 * cumulative R drops below a hard floor. This class is the continuous
 * companion: cells with a sustained positive R get sized UP, cells trending
 * negative but not yet at the suppression floor get sized DOWN, the rest
 * trade at unity. It compounds the empirical winners and de-risks emerging
 * losers without binary on/off behavior.
 *
 * <p>Empirical motivation (14d ending 2026-06-03): five (symbol, direction,
 * strategy) cells produced &gt;+5R cumulative each (BCH SHORT TC at +12.76,
 * LTC SHORT TC at +7.11, XLM LONG TC at +7.04, DOGE SHORT TC at +5.27);
 * sizing those at 1.5x compounds the edge. Cells between 0 and −1R sized
 * at 1.0x (no opinion on insufficient drift); cells past −1R but above
 * the −3R suppression floor sized at 0.5x.
 *
 * <p>Fail-open: any query error returns 1.0 (neutral size). A stuck DB
 * read must not refuse legitimate trades; it should only stop UPSIZING
 * legitimate ones.
 *
 * <p>Reuses the same shared-DB native read pattern as
 * {@link SymbolPerformanceGate}. Cached for the same TTL as that gate.
 */
@ApplicationScoped
public class StrategyPerformanceSizer {

    private static final Logger LOG = Logger.getLogger(StrategyPerformanceSizer.class);

    private static final int MIN_SAMPLE_FOR_OPINION = 5;
    private static final double STRONG_WINNER_THRESHOLD_R = 5.0;
    private static final double MODERATE_WINNER_THRESHOLD_R = 2.0;
    private static final double WEAK_LOSER_THRESHOLD_R = -1.0;

    private static final double MULT_STRONG_WINNER = 1.5;
    private static final double MULT_MODERATE_WINNER = 1.25;
    private static final double MULT_NEUTRAL = 1.0;
    private static final double MULT_WEAK_LOSER = 0.5;

    @Inject EntityManager entityManager;
    @Inject ExecutionSettingsService executionSettings;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Runs the lookup OUTSIDE any caller transaction (NOT_SUPPORTED) so a
     * read failure on signal_outcomes — e.g. missing schema in tests, network
     * blip in prod — cannot poison the OrderPlacer transaction that wraps
     * the actual order INSERT. The fail-open path returns the neutral
     * multiplier; trade still places at base size.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public double multiplierFor(String symbol, String direction, String strategy) {
        ExecutionSettingsService.Snapshot s = executionSettings.snapshot();
        if (!s.symbolGateEnabled()) return MULT_NEUTRAL;
        String key = key(symbol, direction, strategy);
        Cached cached = cache.get(key);
        if (cached != null && !cached.isExpired(s.symbolGateCacheTtlSec())) {
            return cached.multiplier();
        }
        Cached fresh = evaluate(symbol, direction, strategy, s.symbolGateLookback());
        cache.put(key, fresh);
        return fresh.multiplier();
    }

    private Cached evaluate(String symbol, String direction, String strategy, int lookback) {
        Stats stats = queryStats(symbol, direction, strategy, lookback);
        if (stats == null) return new Cached(MULT_NEUTRAL, 0, 0.0, Instant.now());
        return new Cached(decideMultiplier(stats), stats.n(), stats.totalR(), Instant.now());
    }

    static double decideMultiplier(Stats stats) {
        if (stats.n() < MIN_SAMPLE_FOR_OPINION) return MULT_NEUTRAL;
        if (stats.totalR() >= STRONG_WINNER_THRESHOLD_R) return MULT_STRONG_WINNER;
        if (stats.totalR() >= MODERATE_WINNER_THRESHOLD_R) return MULT_MODERATE_WINNER;
        if (stats.totalR() <= WEAK_LOSER_THRESHOLD_R) return MULT_WEAK_LOSER;
        return MULT_NEUTRAL;
    }

    Stats queryStats(String symbol, String direction, String strategy, int lookback) {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery(
                            "SELECT COUNT(*) AS n, COALESCE(SUM(realized_r_multiple), 0) AS total_r "
                            + "FROM (SELECT realized_r_multiple FROM signal_outcomes "
                            + "      WHERE symbol = :symbol "
                            + "        AND direction = :direction "
                            + "        AND strategy = :strategy "
                            + "        AND status != 'PENDING' "
                            + "        AND realized_r_multiple IS NOT NULL "
                            + "      ORDER BY fired_at DESC LIMIT :lookback) AS recent")
                    .setParameter("symbol", symbol)
                    .setParameter("direction", direction)
                    .setParameter("strategy", strategy)
                    .setParameter("lookback", lookback)
                    .getSingleResult();
            int n = ((Number) row[0]).intValue();
            double totalR = toDouble(row[1]);
            return new Stats(n, totalR);
        } catch (RuntimeException e) {
            LOG.warnf(e, "StrategyPerformanceSizer query failed for %s/%s/%s — defaulting to neutral",
                    symbol, direction, strategy);
            return null;
        }
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof BigDecimal bd) return bd.doubleValue();
        return 0.0;
    }

    private static String key(String symbol, String direction, String strategy) {
        return symbol + ":" + direction + ":" + strategy;
    }

    public Cached lastDecisionFor(String symbol, String direction, String strategy) {
        return cache.get(key(symbol, direction, strategy));
    }

    record Stats(int n, double totalR) {}

    public record Cached(double multiplier, int sampleSize, double totalR, Instant evaluatedAt) {
        boolean isExpired(int ttlSeconds) {
            long elapsedSeconds = Instant.now().getEpochSecond() - evaluatedAt.getEpochSecond();
            return elapsedSeconds >= ttlSeconds;
        }
    }
}
