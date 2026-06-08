package com.cryptoradar.options.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scores recent signal activity for an underlying as a 0-100 indicator of
 * upcoming volatility. Reads from the shared {@code signal_outcomes} hypertable
 * (owned by signal-service).
 *
 * <p>Higher overlay score = more recent signals at higher alignment = engine
 * is screaming about something on this symbol, which often precedes a move
 * options-friendly enough to overwhelm theta decay.
 */
@ApplicationScoped
public class SignalOverlayService {

    private static final Logger LOG = Logger.getLogger(SignalOverlayService.class);

    // Score-mixing weights. Sum to 1.0. Picked from feel, not fit — adjust once
    // we have ground-truth on closed opportunities.
    private static final double WEIGHT_DENSITY = 0.4;
    private static final double WEIGHT_ALIGNMENT = 0.3;
    private static final double WEIGHT_RECENT_R = 0.3;

    @Inject EntityManager entityManager;

    // Cache score per underlying — same rationale as RealizedVolService. The
    // underlying SQL aggregate is cheap but the Hibernate + chunk-planner
    // overhead dominated. 30s is short enough that "live" callers see fresh
    // numbers as new signals fire, long enough to absorb burst traffic.
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private final ConcurrentHashMap<String, CachedScore> cache = new ConcurrentHashMap<>();

    public double score(String underlying) {
        CachedScore cached = cache.get(underlying);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
        String spotSymbol = underlying + "USDT";
        Stats s = querySignalStats(spotSymbol);
        if (s == null) {
            cache.put(underlying, new CachedScore(0.0, Instant.now()));
            return 0.0;
        }

        // Density: 0 -> 0, 5+ signals in 6h -> 100.
        double densityScore = Math.min(100.0, s.signals6h * 20.0);
        // Alignment: linear 0-100 mapping, clamped.
        double alignmentScore = s.avgAlignment != null
                ? Math.max(0.0, Math.min(100.0, s.avgAlignment))
                : 0.0;
        // Recent abs(R): 0 -> 0, 2R+ -> 100.
        double recentR = s.avgAbsR24h != null ? s.avgAbsR24h : 0.0;
        double recentRScore = Math.min(100.0, recentR * 50.0);

        double score = WEIGHT_DENSITY * densityScore
                + WEIGHT_ALIGNMENT * alignmentScore
                + WEIGHT_RECENT_R * recentRScore;
        cache.put(underlying, new CachedScore(score, Instant.now()));
        return score;
    }

    private record CachedScore(double value, Instant cachedAt) {
        boolean isExpired() {
            return Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) >= 0;
        }
    }

    @SuppressWarnings("unchecked")
    @Transactional
    Stats querySignalStats(String spotSymbol) {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT
                    COUNT(*) FILTER (WHERE fired_at > now() - interval '6 hours') AS signals_6h,
                    AVG(alignment) FILTER (WHERE fired_at > now() - interval '6 hours') AS avg_alignment,
                    AVG(ABS(realized_r_multiple)) FILTER (
                        WHERE status != 'PENDING' AND fired_at > now() - interval '24 hours'
                    ) AS avg_abs_r_24h
                FROM signal_outcomes
                WHERE symbol = :symbol
                """)
                    .setParameter("symbol", spotSymbol)
                    .getSingleResult();
            int signals = row[0] != null ? ((Number) row[0]).intValue() : 0;
            Double avgAlign = row[1] != null ? ((Number) row[1]).doubleValue() : null;
            Double avgAbsR = row[2] != null ? ((Number) row[2]).doubleValue() : null;
            return new Stats(signals, avgAlign, avgAbsR);
        } catch (Exception e) {
            LOG.warnf(e, "SignalOverlay query failed for %s — returning zero", spotSymbol);
            return null;
        }
    }

    record Stats(int signals6h, Double avgAlignment, Double avgAbsR24h) {}
}
