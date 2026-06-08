package com.cryptoradar.options.repository;

import com.cryptoradar.options.model.OptionShortVolOpportunity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Persistence + dedup for short-vol opportunities. Same pattern as
 * {@link OptionOpportunityRepository} — kept separate so the two strategies
 * can evolve their schemas independently.
 */
@ApplicationScoped
public class OptionShortVolOpportunityRepository {

    @Inject EntityManager entityManager;

    @Transactional
    public void persist(OptionShortVolOpportunity opp) {
        entityManager.persist(opp);
    }

    /**
     * Dedup window — true when an unresolved short-vol setup already exists
     * on the same short legs within the cooldown.
     */
    @Transactional
    public boolean existsOpenForShortLegs(String shortCallSymbol, String shortPutSymbol,
                                           int cooldownMinutes) {
        Object result = entityManager.createNativeQuery("""
            SELECT EXISTS (
              SELECT 1 FROM option_short_vol_opportunities
              WHERE (short_call_symbol = :call OR short_call_symbol IS NULL AND :call IS NULL)
                AND (short_put_symbol = :put OR short_put_symbol IS NULL AND :put IS NULL)
                AND outcome_resolved_at IS NULL
                AND detected_at > now() - (:cooldown || ' minutes')::interval
            )
            """)
                .setParameter("call", shortCallSymbol)
                .setParameter("put", shortPutSymbol)
                .setParameter("cooldown", cooldownMinutes)
                .getSingleResult();
        return result instanceof Boolean b && b;
    }

    @Transactional
    public List<OptionShortVolOpportunity> findRecent(int limit) {
        return entityManager.createQuery(
                        "SELECT o FROM OptionShortVolOpportunity o ORDER BY o.detectedAt DESC",
                        OptionShortVolOpportunity.class)
                .setMaxResults(limit)
                .getResultList();
    }

    @Transactional
    public List<OptionShortVolOpportunity> findOpen(int limit) {
        return entityManager.createQuery(
                        "SELECT o FROM OptionShortVolOpportunity o "
                                + "WHERE o.outcomeResolvedAt IS NULL "
                                + "ORDER BY o.detectedAt DESC",
                        OptionShortVolOpportunity.class)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * Used by the Tier 3 evaluation pipeline (deflated Sharpe) to pull the
     * realized R distribution per strategy. {@code outcomePnlPct} is in %
     * of max-loss-collected; treat as the R-multiple equivalent.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<Double> recentClosedReturnsPct(int limit) {
        return entityManager.createNativeQuery("""
            SELECT outcome_pnl_pct FROM option_short_vol_opportunities
            WHERE outcome_resolved_at IS NOT NULL AND outcome_pnl_pct IS NOT NULL
            ORDER BY outcome_resolved_at DESC
            LIMIT :limit
            """)
                .setParameter("limit", limit)
                .getResultList();
    }
}
