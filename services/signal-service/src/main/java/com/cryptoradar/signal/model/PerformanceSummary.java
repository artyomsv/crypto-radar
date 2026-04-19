package com.cryptoradar.signal.model;

/**
 * Aggregate performance metrics for a set of signal outcomes.
 *
 * <p>All monetary terms are in "R units" (multiples of risk) so results are
 * position-size independent and comparable across signals of different
 * volatilities.
 */
public record PerformanceSummary(
        int total,
        int pending,
        int hitTarget,
        int hitStop,
        int expired,
        double winRate,
        double avgRMultiple,
        double totalRMultiple,
        double bestRMultiple,
        double worstRMultiple,
        double profitFactor,
        double avgMaxFavorablePct,
        double avgMaxAdversePct
) {
    public static PerformanceSummary empty() {
        return new PerformanceSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
