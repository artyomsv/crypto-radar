package com.cryptoradar.options.service;

import com.cryptoradar.options.model.OptionOpportunity;
import com.cryptoradar.options.model.OptionShortVolOpportunity;
import com.cryptoradar.options.repository.OptionOpportunityRepository;
import com.cryptoradar.options.repository.OptionShortVolOpportunityRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tier 3 — López de Prado triple-barrier outcome resolver.
 *
 * <p>For every opportunity (long-vol AND short-vol) whose expiry has passed
 * and outcome has not yet been resolved, this job computes the realized
 * outcome by simulating a paper hold to expiry against the spot price at
 * expiry. Writes {@code outcome_label} (WIN / LOSS / EXPIRED) plus
 * {@code outcome_pnl_pct} so the evaluation pipeline can score the
 * strategies themselves, not just the signals they produce.
 *
 * <h3>Long-vol labeling</h3>
 * <pre>
 *   realized_move_pct = |spot_at_expiry - spot_at_entry| / spot_at_entry
 *   break_even_pct    = strangle_premium / spot_at_entry
 *   WIN  if realized_move_pct &gt; break_even_pct
 *   LOSS if realized_move_pct &lt; 0.5 * break_even_pct (strangle decayed materially)
 *   else EXPIRED
 * </pre>
 *
 * <h3>Short-vol labeling</h3>
 * <pre>
 *   WIN   if spot_at_expiry within [break_even_low, break_even_high]
 *   LOSS  if spot_at_expiry breaches either band (max-loss-collected)
 *   pnl_usd = either net_credit (WIN) or -(max_loss - net_credit) (LOSS)
 *   pnl_pct relative to max_loss
 * </pre>
 *
 * <p>Runs once per day. Cheap query — only acts on rows whose expiry
 * &lt; today AND outcome_resolved_at IS NULL.
 */
@ApplicationScoped
public class TripleBarrierResolver {

    private static final Logger LOG = Logger.getLogger(TripleBarrierResolver.class);
    private static final String LABEL_WIN = "WIN";
    private static final String LABEL_LOSS = "LOSS";
    private static final String LABEL_EXPIRED = "EXPIRED";

    @Inject OptionOpportunityRepository longRepo;
    @Inject OptionShortVolOpportunityRepository shortRepo;
    @Inject EntityManager em;

    /**
     * Cron — once per day at 02:30 UTC. After the daily candle closes so
     * the {@code closing_spot} lookup hits real data.
     */
    @Scheduled(cron = "{scheduler.outcome-resolver.cron}", identity = "options-outcome-resolver")
    public void resolveDailyBatch() {
        try {
            int longResolved = resolveLongVol();
            int shortResolved = resolveShortVol();
            if (longResolved + shortResolved > 0) {
                LOG.infof("triple-barrier resolved %d long-vol and %d short-vol opportunities",
                        longResolved, shortResolved);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "triple-barrier daily resolver failed — will retry next cron");
        }
    }

    @Transactional
    int resolveLongVol() {
        @SuppressWarnings("unchecked")
        List<OptionOpportunity> unresolved = em.createQuery(
                        "SELECT o FROM OptionOpportunity o "
                                + "WHERE o.outcomeResolvedAt IS NULL "
                                + "AND o.expiry < CURRENT_DATE",
                        OptionOpportunity.class)
                .getResultList();
        int resolved = 0;
        for (OptionOpportunity o : unresolved) {
            try {
                if (resolveLongVolOne(o)) resolved++;
            } catch (RuntimeException e) {
                LOG.warnf(e, "long-vol resolution failed for id=%d — skip", o.getId());
            }
        }
        return resolved;
    }

    private boolean resolveLongVolOne(OptionOpportunity o) {
        Double entrySpot = readEntrySpot(o.getMetadata());
        Double expirySpot = spotOnDate(o.getUnderlying() + "USDT", o.getExpiry());
        if (entrySpot == null || expirySpot == null || entrySpot <= 0) return false;

        double realizedMovePct = Math.abs(expirySpot - entrySpot) / entrySpot * 100.0;
        double breakEvenPct = o.getStranglePremium() / entrySpot * 100.0;
        String label;
        double pnlPct;
        if (realizedMovePct > breakEvenPct) {
            label = LABEL_WIN;
            pnlPct = (realizedMovePct - breakEvenPct);
        } else if (realizedMovePct < 0.5 * breakEvenPct) {
            label = LABEL_LOSS;
            pnlPct = -(breakEvenPct - realizedMovePct);
        } else {
            label = LABEL_EXPIRED;
            pnlPct = -(breakEvenPct - realizedMovePct);
        }
        o.setRealizedMovePct(realizedMovePct);
        o.setOutcomePnlPct(pnlPct);
        o.setOutcomeLabel(label);
        o.setOutcomeResolvedAt(java.time.Instant.now());
        return true;
    }

    @Transactional
    int resolveShortVol() {
        @SuppressWarnings("unchecked")
        List<OptionShortVolOpportunity> unresolved = em.createQuery(
                        "SELECT o FROM OptionShortVolOpportunity o "
                                + "WHERE o.outcomeResolvedAt IS NULL "
                                + "AND o.expiry < CURRENT_DATE",
                        OptionShortVolOpportunity.class)
                .getResultList();
        int resolved = 0;
        for (OptionShortVolOpportunity o : unresolved) {
            try {
                if (resolveShortVolOne(o)) resolved++;
            } catch (RuntimeException e) {
                LOG.warnf(e, "short-vol resolution failed for id=%d — skip", o.getId());
            }
        }
        return resolved;
    }

    private boolean resolveShortVolOne(OptionShortVolOpportunity o) {
        Double expirySpot = spotOnDate(o.getUnderlying() + "USDT", o.getExpiry());
        if (expirySpot == null) return false;

        String label;
        double pnlUsd;
        boolean lowBreached = o.getBreakEvenLow() != null && expirySpot < o.getBreakEvenLow();
        boolean highBreached = o.getBreakEvenHigh() != null && expirySpot > o.getBreakEvenHigh();
        if (!lowBreached && !highBreached) {
            label = LABEL_WIN;
            pnlUsd = o.getNetCredit();
        } else {
            label = LABEL_LOSS;
            pnlUsd = -(o.getMaxLossUsd() - o.getNetCredit());
        }
        double pnlPct = o.getMaxLossUsd() > 0 ? pnlUsd / o.getMaxLossUsd() * 100.0 : 0;
        o.setOutcomeLabel(label);
        o.setOutcomePnlUsd(pnlUsd);
        o.setOutcomePnlPct(pnlPct);
        o.setOutcomeResolvedAt(java.time.Instant.now());
        return true;
    }

    @SuppressWarnings("unchecked")
    Double spotOnDate(String spotSymbol, LocalDate date) {
        try {
            // Daily candle closing at the date — TimescaleDB candles hypertable.
            // Bounded query so the planner can prune chunks.
            Object result = em.createNativeQuery("""
                SELECT close FROM candles
                WHERE symbol = :symbol AND interval = '1d'
                  AND time >= :start AND time < :end
                ORDER BY time DESC
                LIMIT 1
                """)
                    .setParameter("symbol", spotSymbol)
                    .setParameter("start", date.atStartOfDay().toInstant(ZoneOffset.UTC))
                    .setParameter("end", date.plus(1, ChronoUnit.DAYS).atStartOfDay().toInstant(ZoneOffset.UTC))
                    .getSingleResult();
            return result == null ? null : ((Number) result).doubleValue();
        } catch (jakarta.persistence.NoResultException nre) {
            return null;
        } catch (RuntimeException e) {
            LOG.warnf(e, "spotOnDate failed for %s/%s", spotSymbol, date);
            return null;
        }
    }

    static Double readEntrySpot(java.util.Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object v = metadata.get("underlyingPx");
        if (v == null) v = metadata.get("spot");
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }
}
