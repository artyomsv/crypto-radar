package com.cryptoradar.signal.model;

import java.time.Instant;
import java.util.Map;

/**
 * Full performance report covering a time window.
 * Includes an overall summary plus breakdowns by strategy, signal type, symbol,
 * exit reason, and alignment bucket. Also records the current market regime
 * so consumers can correlate outcomes with the regime classifier's state.
 *
 * <p>The {@code byStrategy} breakdown is the primary comparison view: it
 * answers "which detector is beating which" — the feedback loop's reason
 * for existing. {@code byExitReason} answers "is the trail working?"
 * and {@code byAlignmentBucket} answers "does the alignment score still
 * inverse-correlate with win rate post-PR3?"
 */
public record PerformanceReport(
        Instant from,
        Instant to,
        int periodDays,
        String currentRegime,
        PerformanceSummary overall,
        Map<String, PerformanceSummary> byStrategy,
        Map<String, PerformanceSummary> bySignalType,
        Map<String, PerformanceSummary> bySymbol,
        Map<String, PerformanceSummary> byExitReason,
        Map<String, PerformanceSummary> byAlignmentBucket
) {
}
