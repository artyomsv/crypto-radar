package com.cryptoradar.signal.model;

import java.time.Instant;
import java.util.List;

/**
 * A specific trade setup detected by a {@code TradeSetupDetector}.
 *
 * <p>Unlike {@link TradingSignal} — which represents a continuously-updating
 * dimension-scored bias — a {@code TradeSetup} is a discrete event with
 * preconditions, a trigger, and an explicit entry/stop/target. One detector
 * emits zero-or-one setup per evaluation; multiple detectors can emit
 * setups for the same symbol simultaneously without conflict.
 *
 * <p>Every setup is persisted independently so each strategy's real-world
 * performance can be measured against the others.
 *
 * @param strategy        detector name, e.g. {@code "trend-continuation"}
 * @param symbol          trading pair, e.g. {@code "BTCUSDT"}
 * @param direction       {@code "LONG"} or {@code "SHORT"}
 * @param signalType      compatibility hint for outcome tracker ({@code BUY},
 *                        {@code STRONG_BUY}, {@code SELL}, {@code STRONG_SELL})
 * @param entryPrice      the price at which to enter
 * @param stopPrice       protective stop (invalidates the setup)
 * @param targetPrice     profit target (realizes the setup)
 * @param riskRewardRatio {@code (target - entry) / (entry - stop)}
 * @param confidence      detector-specific confidence 0-100
 * @param reasons         human-readable trail of preconditions that fired
 * @param firedAt         wall clock when the detector fired
 */
public record TradeSetup(
        String strategy,
        String symbol,
        String direction,
        String signalType,
        double entryPrice,
        double stopPrice,
        double targetPrice,
        double riskRewardRatio,
        int confidence,
        List<String> reasons,
        Instant firedAt
) {
}
