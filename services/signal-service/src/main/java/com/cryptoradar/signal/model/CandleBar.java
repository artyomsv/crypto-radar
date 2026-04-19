package com.cryptoradar.signal.model;

import java.time.Instant;

/**
 * Compact OHLC bar used by the outcome evaluator and trade-setup detectors.
 * Mirrors the subset of fields consumed from market-data-service candles;
 * other fields (volume, quoteVolume, tradeCount) are dropped to keep the
 * record lean and avoid binding the signal service to every schema change.
 */
public record CandleBar(Instant time, double open, double high, double low, double close) {
}
