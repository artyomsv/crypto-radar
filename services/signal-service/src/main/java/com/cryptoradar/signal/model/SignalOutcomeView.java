package com.cryptoradar.signal.model;

import java.time.Instant;

/**
 * Read-only projection of a {@link SignalOutcome} suitable for the
 * {@code /api/signals/outcomes} ledger endpoint.
 *
 * <p>Deliberately excludes engine-internal fields ({@code lastEvaluatedAt},
 * {@code overallScore}) that the UI doesn't need. Keeping a view record
 * separate from the JPA entity lets us evolve the persistence schema
 * without breaking the public API shape.
 */
public record SignalOutcomeView(
        String signalId,
        String symbol,
        String strategy,
        String signalType,
        String direction,
        Instant firedAt,
        double entryPrice,
        double stopPrice,
        double targetPrice,
        double riskRewardRatio,
        int confidence,
        String status,
        Instant closedAt,
        Double closedPrice,
        Double realizedPnlPct,
        Double realizedRMultiple,
        double maxFavorablePct,
        double maxAdversePct,
        String aiAnalysis
) {
    public static SignalOutcomeView from(SignalOutcome outcome) {
        return new SignalOutcomeView(
                outcome.getSignalId(),
                outcome.getSymbol(),
                outcome.getStrategy(),
                outcome.getSignalType(),
                outcome.getDirection(),
                outcome.getFiredAt(),
                outcome.getEntryPrice(),
                outcome.getStopPrice(),
                outcome.getTargetPrice(),
                outcome.getRiskRewardRatio(),
                outcome.getConfidence(),
                outcome.getStatus().name(),
                outcome.getClosedAt(),
                outcome.getClosedPrice(),
                outcome.getRealizedPnlPct(),
                outcome.getRealizedRMultiple(),
                outcome.getMaxFavorablePct(),
                outcome.getMaxAdversePct(),
                outcome.getAiAnalysis()
        );
    }
}
