package com.cryptoradar.marketdata.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catches silently delisted or otherwise frozen symbols. Binance does NOT
 * return an error when a pair is delisted — it returns the same stale kline
 * forever. Without explicit detection the scheduler "successfully" stores
 * the same row every minute and downstream signals run on fictional prices.
 *
 * <p>This class adds two independent defenses:
 *
 * <ol>
 *   <li><b>Startup scan</b> — on service boot, query for any (symbol, interval)
 *       whose newest candle is older than 1 hour. Each such row gets a single
 *       WARN line. Catches rot accumulated while the service was down.</li>
 *   <li><b>Per-fetch staleness counter</b> — every call to
 *       {@link #recordFetch} compares the newest fetched bar time against
 *       the previously-seen newest time. If the time has not advanced for
 *       {@code consecutiveStaleFetchesThreshold} consecutive fetches, a WARN
 *       is logged. Caller decides what to do (auto-deactivate, page on-call,
 *       etc.); we just surface the signal.</li>
 * </ol>
 *
 * <p>See techdebt {@code 2-2-silent-delisting-detection-gap.md} — XMRUSDT
 * was frozen for 2+ years before anyone noticed. This is the early-warning.
 */
@ApplicationScoped
public class StalenessDetector {

    private static final Logger LOG = Logger.getLogger(StalenessDetector.class);

    static final Duration STARTUP_STALE_THRESHOLD = Duration.ofHours(1);
    static final int CONSECUTIVE_STALE_FETCHES_THRESHOLD = 3;

    /**
     * After this many consecutive stale fetches a symbol is auto-deactivated.
     * 3 hours of consecutive stale 1m fetches (one fetch per minute = 180) is
     * decisive — Binance has never recovered a delisted pair this quickly, so
     * the symbol is almost certainly gone for good. Auto-deactivation flips
     * {@code crypto_assets.is_active=false} so the scheduler stops wasting
     * API quota on it.
     */
    static final int AUTO_DEACTIVATE_STALE_FETCHES_THRESHOLD = 180;

    @Inject EntityManager entityManager;

    private final ConcurrentHashMap<String, FetchState> perSymbolInterval = new ConcurrentHashMap<>();

    @Transactional
    public void onStart(@Observes StartupEvent event) {
        try {
            runStartupScan();
        } catch (RuntimeException e) {
            LOG.warnf(e, "startup staleness scan failed — continuing");
        }
    }

    /**
     * Lists every (symbol, interval) whose latest candle is more than
     * {@link #STARTUP_STALE_THRESHOLD} old and logs WARN per row. Pure read.
     */
    void runStartupScan() {
        // Restrict to 1m bars: a stale 1m bar is the canonical delisting symptom
        // (Binance returns the same frozen bar every minute). Wider intervals
        // legitimately have low refresh rates (e.g. a 1d bar updates once per
        // day) and would produce noise without a per-interval tolerance.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT symbol, interval, MAX(time) AS latest "
                        + "FROM candles "
                        + "WHERE interval = '1m' "
                        + "GROUP BY symbol, interval "
                        + "HAVING MAX(time) < NOW() - INTERVAL '1 hour' "
                        + "ORDER BY symbol")
                .getResultList();
        if (rows.isEmpty()) {
            LOG.info("startup staleness scan: all (symbol, 1m) candles fresh within 1h");
            return;
        }
        for (Object[] row : rows) {
            String symbol = (String) row[0];
            String interval = (String) row[1];
            Instant latest = toInstant(row[2]);
            if (latest == null) continue;
            Duration age = Duration.between(latest, Instant.now());
            LOG.warnf("STALE_CANDLES %s [%s] latest=%s age=%d minutes — consider delisting?",
                    symbol, interval, latest, age.toMinutes());
        }
    }

    /**
     * The TIMESTAMPTZ → Java type mapping changed over JDBC driver versions:
     * older drivers return {@link Timestamp}, the modern Postgres driver
     * returns {@link OffsetDateTime}. Hibernate's native-query path may
     * surface either depending on classpath. Accept both.
     */
    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Timestamp ts) return ts.toInstant();
        return null;
    }

    /**
     * Called after a successful fetch+upsert. {@code newestEpochMs} is the
     * largest bar time in the just-stored batch. If that time hasn't advanced
     * for {@link #CONSECUTIVE_STALE_FETCHES_THRESHOLD} consecutive calls,
     * logs a WARN identifying the symbol as likely delisted/frozen.
     */
    public void recordFetch(String symbol, String interval, long newestEpochMs) {
        String key = symbol + "|" + interval;
        FetchState state = perSymbolInterval.compute(key, (k, prev) -> {
            if (prev == null) return new FetchState(newestEpochMs, 0);
            if (newestEpochMs > prev.newestEpochMs()) {
                return new FetchState(newestEpochMs, 0);
            }
            return new FetchState(prev.newestEpochMs(), prev.consecutiveStale() + 1);
        });
        if (state.consecutiveStale() == CONSECUTIVE_STALE_FETCHES_THRESHOLD) {
            LOG.warnf("STALE_FETCH %s [%s] latest bar unchanged for %d consecutive fetches "
                            + "(epoch=%d) — likely delisted/frozen",
                    symbol, interval, state.consecutiveStale(), newestEpochMs);
        }
        if (state.consecutiveStale() == AUTO_DEACTIVATE_STALE_FETCHES_THRESHOLD
                && "1m".equals(interval)) {
            // Only auto-deactivate on the 1m channel — that's the canonical
            // delisting signal. Wider intervals' staleness is less decisive.
            deactivateSymbol(symbol, state.consecutiveStale());
        }
    }

    /**
     * Flips {@code crypto_assets.is_active=false}. Idempotent — re-running
     * has no effect. Logged once per deactivation event.
     */
    void deactivateSymbol(String symbol, int consecutiveStale) {
        try {
            int updated = entityManager.createNativeQuery(
                            "UPDATE crypto_assets SET is_active = false "
                            + "WHERE symbol = :symbol AND is_active = true")
                    .setParameter("symbol", symbol)
                    .executeUpdate();
            if (updated > 0) {
                LOG.warnf("AUTO_DEACTIVATED %s — %d consecutive stale 1m fetches; "
                                + "set crypto_assets.is_active=false",
                        symbol, consecutiveStale);
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "auto-deactivate of %s failed", symbol);
        }
    }

    // Test-visible state snapshot. Returns an unmodifiable view.
    Map<String, FetchState> snapshot() {
        return Map.copyOf(perSymbolInterval);
    }

    record FetchState(long newestEpochMs, int consecutiveStale) {}
}
