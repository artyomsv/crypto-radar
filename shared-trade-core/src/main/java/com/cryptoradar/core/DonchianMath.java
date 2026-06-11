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

    /**
     * N = Wilder-smoothed ATR over {@code period} days, the original Turtle
     * volatility unit. True range needs the prior close, so the series must be
     * at least {@code period + 1} long. Seeds with the simple average of the
     * first {@code period} true ranges, then applies Wilder smoothing
     * {@code N = ((period-1)·prevN + TR) / period} for the remainder.
     */
    public static double computeN(double[] highs, double[] lows, double[] closes, int period) {
        int length = highs.length;
        if (period <= 0 || length < period + 1) {
            throw new IllegalArgumentException("computeN: need at least " + (period + 1)
                    + " bars for period " + period + ", got " + length);
        }
        double seedSum = 0.0;
        for (int i = 1; i <= period; i++) {
            seedSum += trueRange(highs[i], lows[i], closes[i - 1]);
        }
        double n = seedSum / period;
        for (int i = period + 1; i < length; i++) {
            double tr = trueRange(highs[i], lows[i], closes[i - 1]);
            n = ((period - 1) * n + tr) / period;
        }
        return n;
    }

    private static double trueRange(double high, double low, double prevClose) {
        double a = high - low;
        double b = Math.abs(high - prevClose);
        double c = Math.abs(low - prevClose);
        return Math.max(a, Math.max(b, c));
    }

    private static void requireWindow(int length, int endExclusive, int lookback, String who) {
        if (lookback <= 0 || endExclusive > length || endExclusive - lookback < 0) {
            throw new IllegalArgumentException(who + ": need " + lookback
                    + " bars before index " + endExclusive + " in a series of " + length
                    + " — not enough history");
        }
    }
}
