package com.cryptoradar.signal.model;

import java.time.Instant;

/**
 * Per-symbol daily Donchian channel snapshot consumed by the breakout
 * detectors. Carries all six channel levels needed across the three
 * strategies plus the Turtle volatility unit N and the System-1 loser-filter
 * flags (one per direction). Computed by {@code DonchianChannelService};
 * injected into {@link MarketContext} so detectors stay pure.
 *
 * @param high20 highest high of the prior 20 completed daily bars
 * @param low20  lowest low of the prior 20 completed daily bars
 * @param high10 highest high of the prior 10 completed daily bars (S1/donchian short exit)
 * @param low10  lowest low of the prior 10 completed daily bars (S1/donchian long exit)
 * @param high55 highest high of the prior 55 completed daily bars (S2 long entry)
 * @param low55  lowest low of the prior 55 completed daily bars (S2 short entry)
 * @param n      Wilder-smoothed 20-day ATR (Turtle N)
 * @param lastS1LongWasWinner  true when the last closed {@code turtle-s1} outcome
 *               for this symbol in the LONG direction had realized R &gt; 0; the
 *               S1 entry filter skips a new LONG breakout when true
 * @param lastS1ShortWasWinner true when the last closed {@code turtle-s1} outcome
 *               for this symbol in the SHORT direction had realized R &gt; 0; the
 *               S1 entry filter skips a new SHORT breakout when true
 * @param computedAt when this snapshot was built
 */
public record DonchianSnapshot(
        double high20, double low20,
        double high10, double low10,
        double high55, double low55,
        double n,
        boolean lastS1LongWasWinner, boolean lastS1ShortWasWinner,
        Instant computedAt) {
}
