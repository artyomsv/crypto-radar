package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.PerformanceReport;
import com.cryptoradar.signal.model.PerformanceSummary;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Turns raw signal outcomes into aggregate performance metrics.
 *
 * <p>Everything is computed at request time — no caching yet. The volume of
 * outcomes is small (a few per symbol per day × 14 symbols) so even a full
 * 90-day scan is cheap. Add caching if the table ever grows past ~100k rows.
 *
 * <p>Slices beyond the core {@code byStrategy / bySignalType / bySymbol}
 * (added in PR5+PR6b): {@code byExitReason} distinguishes TRAIL_STOP from
 * INITIAL_STOP so the trail's contribution is measurable; {@code
 * byAlignmentBucket} groups by the alignment score (formerly "confidence")
 * to validate or refute the inverse-correlation hypothesis over fresh data.
 */
@ApplicationScoped
public class PerformanceMetricsService {

    private final SignalOutcomeRepository repository;
    private final MarketRegimeService regimeService;

    public PerformanceMetricsService(SignalOutcomeRepository repository,
                                     MarketRegimeService regimeService) {
        this.repository = repository;
        this.regimeService = regimeService;
    }

    @Transactional
    public PerformanceReport buildReport(int periodDays) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(periodDays));

        List<SignalOutcome> outcomes = repository.findFiredSince(from);
        PerformanceSummary overall = summarize(outcomes);

        return new PerformanceReport(
                from, to, periodDays,
                regimeService.currentRegime().name(),
                overall,
                groupAndSummarize(outcomes, SignalOutcome::getStrategy),
                groupAndSummarize(outcomes, SignalOutcome::getSignalType),
                groupAndSummarize(outcomes, SignalOutcome::getSymbol),
                groupAndSummarize(outcomes, this::classifyExitReason),
                groupAndSummarize(outcomes, this::classifyAlignmentBucket));
    }

    /**
     * Maps an outcome to its exit-reason label. Open (PENDING) rows report
     * {@code OPEN} so they still contribute to the summary counts without
     * colliding with the close labels.
     */
    private String classifyExitReason(SignalOutcome outcome) {
        if (outcome.getStatus() == OutcomeStatus.PENDING) return "OPEN";
        String reason = outcome.getFinalExitReason();
        return reason != null ? reason : "UNKNOWN";
    }

    /** Coarse alignment buckets for correlation analysis. */
    private String classifyAlignmentBucket(SignalOutcome outcome) {
        Integer alignment = outcome.getAlignment();
        if (alignment == null) return "UNKNOWN";
        if (alignment >= 80) return "80+";
        if (alignment >= 70) return "70-79";
        if (alignment >= 60) return "60-69";
        return "<60";
    }

    private Map<String, PerformanceSummary> groupAndSummarize(
            List<SignalOutcome> outcomes,
            Function<SignalOutcome, String> classifier) {
        Map<String, List<SignalOutcome>> grouped = new TreeMap<>();
        for (SignalOutcome outcome : outcomes) {
            String key = classifier.apply(outcome);
            if (key == null) continue;
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(outcome);
        }

        Map<String, PerformanceSummary> result = new LinkedHashMap<>();
        grouped.forEach((key, group) -> result.put(key, summarize(group)));
        return result;
    }

    private PerformanceSummary summarize(List<SignalOutcome> outcomes) {
        if (outcomes.isEmpty()) return PerformanceSummary.empty();

        SummaryAccumulator acc = new SummaryAccumulator();
        for (SignalOutcome outcome : outcomes) {
            acc.add(outcome);
        }
        return acc.toSummary();
    }

    /**
     * Single-purpose accumulator that folds outcomes into a summary without
     * leaking the arithmetic into the main service method.
     */
    private static final class SummaryAccumulator {
        private int total;
        private int pending;
        private int hitTarget;
        private int hitStop;
        private int expired;
        private double sumR;
        private double sumWins;
        private double sumLossesAbs;
        private double bestR = Double.NEGATIVE_INFINITY;
        private double worstR = Double.POSITIVE_INFINITY;
        private double sumMfe;
        private double sumMae;

        void add(SignalOutcome outcome) {
            total++;
            classifyStatus(outcome);
            accumulatePnl(outcome);
            accumulateExcursions(outcome);
        }

        private void classifyStatus(SignalOutcome outcome) {
            OutcomeStatus status = outcome.getStatus();
            if (status == OutcomeStatus.PENDING)    { pending++;   return; }
            if (status == OutcomeStatus.HIT_TARGET) { hitTarget++; return; }
            if (status == OutcomeStatus.HIT_STOP)   { hitStop++;   return; }
            if (status == OutcomeStatus.EXPIRED)    { expired++; }
        }

        private void accumulatePnl(SignalOutcome outcome) {
            Double rMultiple = outcome.getRealizedRMultiple();
            if (rMultiple == null) return;

            sumR += rMultiple;
            if (rMultiple > bestR)  bestR  = rMultiple;
            if (rMultiple < worstR) worstR = rMultiple;
            if (rMultiple > 0) sumWins += rMultiple;
            else               sumLossesAbs += Math.abs(rMultiple);
        }

        private void accumulateExcursions(SignalOutcome outcome) {
            if (outcome.getMaxFavorablePct() != null) sumMfe += outcome.getMaxFavorablePct();
            if (outcome.getMaxAdversePct() != null)   sumMae += outcome.getMaxAdversePct();
        }

        PerformanceSummary toSummary() {
            int completed = hitTarget + hitStop + expired;
            double winRate = completed > 0 ? (double) hitTarget / completed : 0.0;
            double avgR = completed > 0 ? sumR / completed : 0.0;
            double profitFactor = sumLossesAbs > 0 ? sumWins / sumLossesAbs : sumWins;
            double avgMfe = total > 0 ? sumMfe / total : 0.0;
            double avgMae = total > 0 ? sumMae / total : 0.0;

            double bestFinal = bestR == Double.NEGATIVE_INFINITY ? 0.0 : bestR;
            double worstFinal = worstR == Double.POSITIVE_INFINITY ? 0.0 : worstR;

            return new PerformanceSummary(
                    total, pending, hitTarget, hitStop, expired,
                    winRate, avgR, sumR, bestFinal, worstFinal,
                    profitFactor, avgMfe, avgMae);
        }
    }
}
