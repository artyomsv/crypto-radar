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

/**
 * Turns raw signal outcomes into aggregate performance metrics.
 *
 * <p>Everything is computed at request time — no caching yet. The volume of
 * outcomes is small (a few per symbol per day × 14 symbols) so even a full
 * 90-day scan is cheap. Add caching if the table ever grows past ~100k rows.
 */
@ApplicationScoped
public class PerformanceMetricsService {

    private final SignalOutcomeRepository repository;

    public PerformanceMetricsService(SignalOutcomeRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PerformanceReport buildReport(int periodDays) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(periodDays));

        List<SignalOutcome> outcomes = repository.findFiredSince(from);
        PerformanceSummary overall = summarize(outcomes);
        Map<String, PerformanceSummary> byStrategy = groupAndSummarize(outcomes, SignalOutcome::getStrategy);
        Map<String, PerformanceSummary> bySignalType = groupAndSummarize(outcomes, SignalOutcome::getSignalType);
        Map<String, PerformanceSummary> bySymbol = groupAndSummarize(outcomes, SignalOutcome::getSymbol);

        return new PerformanceReport(from, to, periodDays, overall, byStrategy, bySignalType, bySymbol);
    }

    private Map<String, PerformanceSummary> groupAndSummarize(
            List<SignalOutcome> outcomes,
            java.util.function.Function<SignalOutcome, String> classifier) {
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
