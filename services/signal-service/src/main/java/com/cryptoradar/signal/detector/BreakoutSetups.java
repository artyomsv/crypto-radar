package com.cryptoradar.signal.detector;

import com.cryptoradar.core.DonchianMath;
import com.cryptoradar.signal.model.TradeSetup;

import java.time.Instant;
import java.util.List;

/**
 * Shared construction of the breakout strategies' {@link TradeSetup}. All three
 * detectors size the stop at 2N, carry a distant nominal TP (operative exit is
 * the downstream Donchian monitor), and use a fixed mechanical alignment (these
 * are rule-based, not confluence-scored).
 */
final class BreakoutSetups {

    private BreakoutSetups() {}

    /** Canonical Turtle 2N catastrophic stop. */
    private static final double STOP_MULTIPLE = DonchianMath.STOP_MULTIPLE_2N;
    /** Distant nominal target so the RR-floor passes; real exit is the Donchian monitor. */
    private static final double TARGET_N_MULTIPLE = 20.0;
    /** Fixed alignment — breakouts are rule-based, not confluence-scored. */
    private static final int MECHANICAL_ALIGNMENT = 60;

    /** Parameter object for {@link #build(BreakoutSpec)}. */
    record BreakoutSpec(String strategy, String symbol, double entry, double n,
                        boolean isLong, List<String> reasons, Instant firedAt) {}

    static TradeSetup build(BreakoutSpec spec) {
        double stop = DonchianMath.unitStop(spec.entry(), spec.n(), spec.isLong(), STOP_MULTIPLE);
        double target = spec.isLong()
                ? spec.entry() + TARGET_N_MULTIPLE * spec.n()
                : spec.entry() - TARGET_N_MULTIPLE * spec.n();
        double rr = TARGET_N_MULTIPLE / STOP_MULTIPLE;
        String direction = spec.isLong() ? "LONG" : "SHORT";
        String signalType = spec.isLong() ? "BUY" : "SELL";
        return new TradeSetup(spec.strategy(), spec.symbol(), direction, signalType,
                spec.entry(), stop, target, rr, MECHANICAL_ALIGNMENT, spec.reasons(), spec.firedAt());
    }
}
