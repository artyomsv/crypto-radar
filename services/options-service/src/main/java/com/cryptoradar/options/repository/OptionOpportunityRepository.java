package com.cryptoradar.options.repository;

import com.cryptoradar.options.model.OptionOpportunity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OptionOpportunityRepository {

    @Inject EntityManager entityManager;

    @Transactional
    public void persist(OptionOpportunity opp) {
        entityManager.persist(opp);
    }

    /**
     * Returns true when there is already an unresolved opportunity for the
     * same legs (callSymbol + putSymbol) detected within the cooldown window.
     * The scorer uses this to skip writes — otherwise it fires every 60s
     * and we get N duplicate rows per actual setup.
     */
    @Transactional
    public boolean existsOpenForLegs(String callSymbol, String putSymbol, int cooldownMinutes) {
        Object result = entityManager.createNativeQuery("""
            SELECT EXISTS (
              SELECT 1 FROM option_opportunities
              WHERE call_symbol = :call
                AND put_symbol = :put
                AND outcome_resolved_at IS NULL
                AND detected_at > now() - (:cooldown || ' minutes')::interval
            )
            """)
                .setParameter("call", callSymbol)
                .setParameter("put", putSymbol)
                .setParameter("cooldown", cooldownMinutes)
                .getSingleResult();
        return result instanceof Boolean b && b;
    }

    @Transactional
    public Optional<OptionOpportunity> findById(long id) {
        return Optional.ofNullable(entityManager.find(OptionOpportunity.class, id));
    }

    @SuppressWarnings("unchecked")
    @Transactional
    public List<OptionOpportunity> findRecent(int limit) {
        return entityManager.createQuery(
                "SELECT o FROM OptionOpportunity o ORDER BY o.detectedAt DESC")
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * Returns the freshest open opportunity per unique (underlying, expiry,
     * call_symbol, put_symbol) tuple. The scorer fires every 60s and inserts
     * a new row whenever confidence ≥ threshold — without this DISTINCT ON
     * the UI would fill with near-identical cards. We keep all history in DB
     * (useful for backfill + analysis) but the UI only sees the latest sighting.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<OptionOpportunity> findOpen(int limit) {
        return entityManager.createNativeQuery("""
            SELECT DISTINCT ON (underlying, expiry, call_symbol, put_symbol) *
            FROM option_opportunities
            WHERE outcome_resolved_at IS NULL
            ORDER BY underlying, expiry, call_symbol, put_symbol, detected_at DESC
            """, OptionOpportunity.class)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * Win rate per (underlying, confidence-bucket) across resolved opportunities.
     * Returns one row per non-empty bucket. Caller (UI) hides buckets with
     * {@code sample_size &lt; 10} — small samples mislead.
     *
     * <p>Bucket boundaries: 50-60, 60-70, 70-80, 80-90, 90+ (matches the
     * scorer's threshold layout — 70 is the alert floor).
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<Object[]> hitRateByBucket() {
        return entityManager.createNativeQuery("""
            SELECT
                underlying,
                CASE
                    WHEN confidence >= 90 THEN '90+'
                    WHEN confidence >= 80 THEN '80-90'
                    WHEN confidence >= 70 THEN '70-80'
                    WHEN confidence >= 60 THEN '60-70'
                    ELSE '50-60'
                END AS bucket,
                COUNT(*)                                                AS sample_size,
                AVG(CASE WHEN outcome_pnl_pct > 0 THEN 1.0 ELSE 0.0 END) AS win_rate,
                AVG(outcome_pnl_pct)                                    AS avg_pnl_pct
            FROM option_opportunities
            WHERE outcome_resolved_at IS NOT NULL
              AND outcome_pnl_pct IS NOT NULL
              AND confidence >= 50
            GROUP BY underlying, bucket
            ORDER BY underlying, bucket
            """).getResultList();
    }
}
