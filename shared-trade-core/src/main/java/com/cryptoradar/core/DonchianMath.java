package com.cryptoradar.core;

/**
 * Pure Donchian-channel + Turtle volatility math. No I/O, no state — mirrors
 * {@link RUnitMath}. All array methods take an oldest-first series and an
 * {@code endExclusive} index so callers can exclude the current forming bar
 * (a breakout is "price exceeds the PRIOR n completed bars", not including today).
 */
public final class DonchianMath {

    private DonchianMath() {}

    /** Breakout classification of a live price against a channel. */
    public enum Breakout { LONG, SHORT, NONE }

    /** Highest high over {@code [endExclusive-lookback, endExclusive)}. */
    public static double channelHigh(double[] highs, int endExclusive, int lookback) {
        requireWindow(highs.length, endExclusive, lookback, "channelHigh");
        double max = Double.NEGATIVE_INFINITY;
        for (int i = endExclusive - lookback; i < endExclusive; i++) {
            if (highs[i] > max) max = highs[i];
        }
        return max;
    }

    /** Lowest low over {@code [endExclusive-lookback, endExclusive)}. */
    public static double channelLow(double[] lows, int endExclusive, int lookback) {
        requireWindow(lows.length, endExclusive, lookback, "channelLow");
        double min = Double.POSITIVE_INFINITY;
        for (int i = endExclusive - lookback; i < endExclusive; i++) {
            if (lows[i] < min) min = lows[i];
        }
        return min;
    }

    /** LONG if price breaks above the high channel, SHORT below the low, else NONE. */
    public static Breakout breakoutDirection(double price, double channelHigh, double channelLow) {
        if (price > channelHigh) return Breakout.LONG;
        if (price < channelLow) return Breakout.SHORT;
        return Breakout.NONE;
    }

    private static void requireWindow(int length, int endExclusive, int lookback, String who) {
        if (lookback <= 0 || endExclusive > length || endExclusive - lookback < 0) {
            throw new IllegalArgumentException(who + ": need " + lookback
                    + " bars before index " + endExclusive + " in a series of " + length
                    + " — not enough history");
        }
    }
}
