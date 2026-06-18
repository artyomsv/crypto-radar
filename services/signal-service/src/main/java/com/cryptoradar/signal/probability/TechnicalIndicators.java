package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;

import java.util.List;

/**
 * Classic candle-derived indicators — the raw "instruments" (RSI, Bollinger,
 * MACD, momentum, realized vol, volume) the probability model logs as features
 * for Phase 2. Pure: computed from a bar list, no I/O, fully unit-testable.
 *
 * <p>These are the un-aggregated signals the six dimension scores hide behind a
 * black-box sum, so capturing them is what lets a rich Phase 2 model improve on
 * the dimension-score baseline.
 */
public record TechnicalIndicators(
        double rsi14,
        double bollingerPercentB,
        double macdHistogram,
        double momentum10,
        double realizedVolPct,
        double volumeRatio) {

    private static final int RSI_PERIOD = 14;
    private static final int BB_PERIOD = 20;
    private static final double BB_STDDEV = 2.0;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int MOMENTUM_LOOKBACK = 10;
    private static final int MIN_BARS = MACD_SLOW + MACD_SIGNAL; // 35

    /** Returns null when there are too few bars for the slowest indicator (MACD). */
    public static TechnicalIndicators compute(List<CandleBar> bars) {
        if (bars == null || bars.size() < MIN_BARS) return null;
        double[] closes = bars.stream().mapToDouble(CandleBar::close).toArray();
        return new TechnicalIndicators(
                rsi(closes, RSI_PERIOD),
                bollingerPercentB(closes, BB_PERIOD, BB_STDDEV),
                macdHistogram(closes),
                momentum(closes, MOMENTUM_LOOKBACK),
                realizedVolPct(closes),
                volumeRatio(bars));
    }

    static double rsi(double[] closes, int period) {
        double gain = 0, loss = 0;
        int start = closes.length - period;
        for (int i = start; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            if (change >= 0) gain += change; else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    static double bollingerPercentB(double[] closes, int period, double k) {
        int start = closes.length - period;
        double mean = 0;
        for (int i = start; i < closes.length; i++) mean += closes[i];
        mean /= period;
        double var = 0;
        for (int i = start; i < closes.length; i++) var += (closes[i] - mean) * (closes[i] - mean);
        double sd = Math.sqrt(var / period);
        double upper = mean + k * sd;
        double lower = mean - k * sd;
        double price = closes[closes.length - 1];
        if (upper == lower) return 0.5;
        return (price - lower) / (upper - lower);
    }

    static double macdHistogram(double[] closes) {
        double[] fast = ema(closes, MACD_FAST);
        double[] slow = ema(closes, MACD_SLOW);
        double[] macdLine = new double[closes.length];
        for (int i = 0; i < closes.length; i++) macdLine[i] = fast[i] - slow[i];
        double[] signal = ema(macdLine, MACD_SIGNAL);
        return macdLine[closes.length - 1] - signal[closes.length - 1];
    }

    /** EMA over a series, seeded with the first value. */
    static double[] ema(double[] series, int period) {
        double[] out = new double[series.length];
        double multiplier = 2.0 / (period + 1);
        out[0] = series[0];
        for (int i = 1; i < series.length; i++) {
            out[i] = (series[i] - out[i - 1]) * multiplier + out[i - 1];
        }
        return out;
    }

    static double momentum(double[] closes, int lookback) {
        int idx = closes.length - 1 - lookback;
        if (idx < 0) return 0.0;
        double past = closes[idx];
        return past == 0 ? 0.0 : (closes[closes.length - 1] - past) / past;
    }

    /** Stddev of log returns over the available window, as a percent. */
    static double realizedVolPct(double[] closes) {
        double[] logReturns = new double[closes.length - 1];
        double mean = 0;
        for (int i = 1; i < closes.length; i++) {
            logReturns[i - 1] = Math.log(closes[i] / closes[i - 1]);
            mean += logReturns[i - 1];
        }
        mean /= logReturns.length;
        double var = 0;
        for (double r : logReturns) var += (r - mean) * (r - mean);
        return Math.sqrt(var / logReturns.length) * 100.0;
    }

    static double volumeRatio(List<CandleBar> bars) {
        double last = bars.get(bars.size() - 1).volume();
        double sum = 0;
        for (CandleBar b : bars) sum += b.volume();
        double avg = sum / bars.size();
        return avg <= 0 ? 0.0 : last / avg;
    }
}
