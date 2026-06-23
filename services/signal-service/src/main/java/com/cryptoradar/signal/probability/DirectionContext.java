package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.TradingSignal;

import java.util.List;
import java.util.Map;

/**
 * Everything a {@link CandidateGenerator} needs to choose a direction and build
 * geometry for one symbol at scan time. {@code indicators} is null when there are
 * too few candles for the slowest indicator. Computed once per symbol and shared
 * across all generators in the scan.
 */
public record DirectionContext(
        TradingSignal signal,
        List<CandleBar> bars,
        double atr,
        double entry,
        TechnicalIndicators indicators,
        Map<String, Double> dimScores) {}
