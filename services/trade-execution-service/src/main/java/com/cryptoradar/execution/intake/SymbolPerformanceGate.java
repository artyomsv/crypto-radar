package com.cryptoradar.execution.intake;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vector A — per-symbol rolling performance gate. Phase 2 outcome analysis
 * showed four symbols (LTCUSDT, ETHUSDT, SOLUSDT, XRPUSDT) absorbed the
 * entire −19.77R loss across 34 trades. This gate reads the last N closed
 * outcomes for a symbol from the shared {@code signal_outcomes} hypertable
 * and suppresses real-exchange dispatch when cumulative R falls below a
 * configured floor.
 *
 * <p>Coupling note. trade-execution-service and signal-service share the
 * same TimescaleDB database. This gate issues a native read-only query
 * directly against {@code signal_outcomes} rather than calling signal-
 * service over HTTP, trading a schema coupling (known, documented in
 * project CLAUDE.md) for fewer moving parts on a hot path. If either
 * service moves to a separate DB later, promote to an HTTP client with
 * timeout + fail-open.
 *
 * <p>Fail-open. Any query error (DB unavailable, schema mismatch, parse)
 * is logged at WARN and returns "not suppressed" so a stuck gate cannot
 * block legitimate trades. The gate is enrichment, not safety-critical.
 *
 * <p>Cache. Results are cached for a short TTL to avoid hammering the
 * DB on every incoming overview tick (~13 symbols × every 5s).
 */
@ApplicationScoped
public class SymbolPerformanceGate {

    private static final Logger LOG = Logger.getLogger(SymbolPerformanceGate.class);

    @Inject
    EntityManager entityManager;

    @Inject
    ExecutionSettingsService executionSettings;

    private final Map<String, CachedDecision> cache = new ConcurrentHashMap<>();

    /**
     * Runs OUTSIDE the caller's transaction (NOT_SUPPORTED) so a failing
     * native query on {@code signal_outcomes} — e.g. PostgreSQL
     * in_failed_sql_transaction, missing column during schema migration,
     * network blip — cannot poison the SignalSubscriber transaction that
     * wraps order dispatch. The fail-open path returns "not suppressed";
     * the legitimate dispatch proceeds at full risk. See
     * {@code techdebt/trade-execution-service/3-1-symbol-perf-gate-tx-poisoning.md}.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public boolean isSuppressed(String symbol) {
        ExecutionSettingsService.Snapshot s = executionSettings.snapshot();
        if (!s.symbolGateEnabled()) return false;
        CachedDecision cached = cache.get(symbol);
        if (cached != null && !cached.isExpired(s.symbolGateCacheTtlSec())) {
            return cached.suppressed();
        }
        CachedDecision fresh = evaluateFresh(symbol, s);
        cache.put(symbol, fresh);
        return fresh.suppressed();
    }

    private CachedDecision evaluateFresh(String symbol, ExecutionSettingsService.Snapshot s) {
        Double totalR = queryTotalR(symbol, s.symbolGateLookback());
        if (totalR == null) {
            return new CachedDecision(false, 0.0, 0, Instant.now());
        }
        boolean suppress = totalR <= s.symbolGateThresholdR();
        return new CachedDecision(suppress, totalR, s.symbolGateLookback(), Instant.now());
    }

    Double queryTotalR(String symbol, int lookback) {
        try {
            Object result = entityManager.createNativeQuery(
                            "SELECT COALESCE(SUM(realized_r_multiple), 0) "
                            + "FROM (SELECT realized_r_multiple FROM signal_outcomes "
                            + "      WHERE symbol = :symbol "
                            + "        AND status != 'PENDING' "
                            + "        AND realized_r_multiple IS NOT NULL "
                            + "      ORDER BY fired_at DESC LIMIT :lookback) AS recent")
                    .setParameter("symbol", symbol)
                    .setParameter("lookback", lookback)
                    .getSingleResult();
            return toDouble(result);
        } catch (RuntimeException e) {
            LOG.warnf(e, "SymbolPerformanceGate query failed for %s — failing open", symbol);
            return null;
        }
    }

    private static Double toDouble(Object result) {
        if (result == null) return null;
        if (result instanceof Number number) return number.doubleValue();
        if (result instanceof BigDecimal bd) return bd.doubleValue();
        return null;
    }

    public CachedDecision lastDecisionFor(String symbol) {
        return cache.get(symbol);
    }

    record CachedDecision(boolean suppressed, double totalR, int sampleSize, Instant evaluatedAt) {
        boolean isExpired(int ttlSeconds) {
            long elapsedSeconds = Instant.now().getEpochSecond() - evaluatedAt.getEpochSecond();
            return elapsedSeconds >= ttlSeconds;
        }
    }
}
