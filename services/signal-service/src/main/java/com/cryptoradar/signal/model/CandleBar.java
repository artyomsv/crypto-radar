package com.cryptoradar.signal.model;

import java.time.Instant;

/**
 * Compact OHLCV bar used by the outcome evaluator and trade-setup detectors.
 * Mirrors the subset of fields consumed from market-data-service candles —
 * {@code quoteVolume} and {@code tradeCount} are still dropped to keep the
 * record lean.
 *
 * <p>Volume was added in PR6 to enable liquidity-sweep volume confirmation
 * (genuine stop-hunts run volume; quiet wicks are noise). Bars constructed
 * via the legacy 5-argument constructor default {@code volume} to {@code 0},
 * which detectors should treat as "volume data unavailable, skip the filter"
 * rather than "zero volume, reject".
 *
 * @param time   bar open time
 * @param open   open price
 * @param high   high price
 * @param low    low price
 * @param close  close price
 * @param volume base-asset volume traded during the bar; 0 means unknown
 */
public record CandleBar(Instant time, double open, double high, double low, double close, double volume) {

    /**
     * Legacy constructor — test code and anywhere that doesn't care about
     * volume can keep using 5 args. Defaults volume to 0 so downstream code
     * can distinguish "known zero" from "unknown" via upstream context.
     */
    public CandleBar(Instant time, double open, double high, double low, double close) {
        this(time, open, high, low, close, 0.0);
    }
}
