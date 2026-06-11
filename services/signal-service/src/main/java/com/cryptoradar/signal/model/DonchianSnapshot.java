package com.cryptoradar.signal.model;

import java.time.Instant;

/**
 * Per-symbol daily Donchian channel snapshot consumed by the breakout
 * detectors. Carries all six channel levels needed across the three
 * strategies plus the Turtle volatility unit N and the System-1 loser-filter
 * flag. Computed by {@code DonchianChannelService}; injected into
 * {@link MarketContext} so detectors stay pure.
 *
 * @param high20 highest high of the prior 20 completed daily bars
 * @param low20  lowest low of the prior 20 completed daily bars
 * @param high10 highest high of the prior 10 completed daily bars (S1/donchian short exit)
 * @param low10  lowest low of the prior 10 completed daily bars (S1/donchian long exit)
 * @param high55 highest high of the prior 55 completed daily bars (S2 long entry)
 * @param low55  lowest low of the prior 55 completed daily bars (S2 short entry)
 * @param n      Wilder-smoothed 20-day ATR (Turtle N)
 * @param lastS1BreakoutWasWinner true when the last closed {@code turtle-s1}
 *               outcome for this symbol had realized R &gt; 0; the System-1
 *               entry filter skips a new breakout when this is true
 * @param computedAt when this snapshot was built
 */
public record DonchianSnapshot(
        double high20, double low20,
        double high10, double low10,
        double high55, double low55,
        double n,
        boolean lastS1BreakoutWasWinner,
        Instant computedAt) {
}
