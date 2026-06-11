package com.cryptoradar.signal.detector;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.TradeSetup;

import java.time.Instant;
import java.util.List;

/**
 * Shared construction of the breakout strategies' {@link TradeSetup}. All three
 * detectors size the stop at 2N, carry a distant {@code targetNMultiple}·N
 * nominal TP (the operative exit is the downstream Donchian monitor), and use a
 * fixed mechanical alignment (these are rule-based, not confluence-scored).
 */
final class BreakoutSetups {

    private BreakoutSetups() {}

    static TradeSetup build(String strategy, String symbol, double entry, double n,
                            boolean isLong, double stopMultiple, double targetNMultiple,
                            int alignment, List<String> reasons, Instant firedAt) {
        double stop = DonchianMath.unitStop(entry, n, isLong, stopMultiple);
        double target = isLong ? entry + targetNMultiple * n : entry - targetNMultiple * n;
        double rr = stopMultiple == 0 ? 0 : targetNMultiple / stopMultiple;
        String direction = isLong ? "LONG" : "SHORT";
        String signalType = isLong ? "BUY" : "SELL";
        return new TradeSetup(strategy, symbol, direction, signalType,
                entry, stop, target, rr, alignment, reasons, firedAt);
    }
}
