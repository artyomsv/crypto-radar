package com.cryptoradar.signal.model;

import java.time.Instant;
import java.util.Map;

/**
 * Full performance report covering a time window.
 * Includes an overall summary plus breakdowns by strategy, signal type, and symbol.
 *
 * <p>The {@code byStrategy} breakdown is the primary comparison view: it
 * answers "which detector is beating which" — the feedback loop's reason
 * for existing.
 */
public record PerformanceReport(
        Instant from,
        Instant to,
        int periodDays,
        PerformanceSummary overall,
        Map<String, PerformanceSummary> byStrategy,
        Map<String, PerformanceSummary> bySignalType,
        Map<String, PerformanceSummary> bySymbol
) {
}
