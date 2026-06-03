package com.cryptoradar.options.resource.dto;

/**
 * Historical win rate for resolved opportunities, bucketed by underlying and
 * confidence band. The UI hides this strip until {@code sampleSize >= 10}
 * — small samples are misleading and we never want to fabricate signal
 * where the data isn't there.
 *
 * @param confidenceBucket short label like "70-80" or "90+"
 * @param winRate fraction (0.0 to 1.0) of bucket trades where outcomePnlPct > 0
 * @param avgPnlPct mean outcomePnlPct across the bucket (signed)
 */
public record HitRateView(
        String underlying,
        String confidenceBucket,
        long sampleSize,
        Double winRate,
        Double avgPnlPct
) {}
