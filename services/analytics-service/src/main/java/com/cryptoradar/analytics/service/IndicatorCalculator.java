package com.cryptoradar.analytics.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class IndicatorCalculator {

    /**
     * Standard RSI: Wilder's smoothing method.
     * 1. Price changes = close[i] - close[i-1]
     * 2. Separate into gains (positive) and losses (abs of negative)
     * 3. First avg gain/loss = SMA over first 'period' changes
     * 4. Subsequent: smoothed via (prev * (period-1) + current) / period
     * 5. RS = avgGain / avgLoss, RSI = 100 - 100/(1+RS)
     */
    public Double calculateRSI(List<Double> closes, int period) {
        if (closes == null || closes.size() <= period) {
            return null;
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        for (int i = 1; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            gains.add(Math.max(change, 0.0));
            losses.add(Math.max(-change, 0.0));
        }

        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 0; i < period; i++) {
            avgGain += gains.get(i);
            avgLoss += losses.get(i);
        }
        avgGain /= period;
        avgLoss /= period;

        // Wilder's smoothing for remaining values
        for (int i = period; i < gains.size(); i++) {
            avgGain = (avgGain * (period - 1) + gains.get(i)) / period;
            avgLoss = (avgLoss * (period - 1) + losses.get(i)) / period;
        }

        if (avgLoss == 0.0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Exponential Moving Average.
     * Multiplier = 2 / (period + 1)
     * Seed EMA with SMA of first 'period' values.
     * EMA[i] = (value - prevEMA) * multiplier + prevEMA
     */
    public Double calculateEMA(List<Double> values, int period) {
        if (values == null || values.size() < period) {
            return null;
        }

        double multiplier = 2.0 / (period + 1);

        // Seed with SMA of first 'period' values
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += values.get(i);
        }
        ema /= period;

        // Apply EMA formula for remaining values
        for (int i = period; i < values.size(); i++) {
            ema = (values.get(i) - ema) * multiplier + ema;
        }

        return ema;
    }

    /**
     * Simple Moving Average over the last 'period' values.
     */
    public Double calculateSMA(List<Double> values, int period) {
        if (values == null || values.size() < period) {
            return null;
        }

        double sum = 0.0;
        int start = values.size() - period;
        for (int i = start; i < values.size(); i++) {
            sum += values.get(i);
        }
        return sum / period;
    }

    /**
     * MACD (12, 26, 9).
     * Returns double[3] = {macdLine, signal, histogram}.
     * 1. EMA12 of closes
     * 2. EMA26 of closes
     * 3. MACD line = EMA12 - EMA26
     * 4. Build MACD line series, then signal = EMA(9) of that series
     * 5. Histogram = MACD line - signal
     */
    public double[] calculateMACD(List<Double> closes) {
        if (closes == null || closes.size() < 26) {
            return null;
        }

        double multiplier12 = 2.0 / (12 + 1);
        double multiplier26 = 2.0 / (26 + 1);

        // Build full EMA12 and EMA26 series starting from index 25 (need 26 values for EMA26 seed)
        double ema12 = 0.0;
        for (int i = 0; i < 12; i++) {
            ema12 += closes.get(i);
        }
        ema12 /= 12;

        double ema26 = 0.0;
        for (int i = 0; i < 26; i++) {
            ema26 += closes.get(i);
        }
        ema26 /= 26;

        // Advance EMA12 from index 12 to 25
        for (int i = 12; i < 26; i++) {
            ema12 = (closes.get(i) - ema12) * multiplier12 + ema12;
        }

        // Build MACD line series from index 26 onwards
        List<Double> macdSeries = new ArrayList<>();
        macdSeries.add(ema12 - ema26);

        for (int i = 26; i < closes.size(); i++) {
            ema12 = (closes.get(i) - ema12) * multiplier12 + ema12;
            ema26 = (closes.get(i) - ema26) * multiplier26 + ema26;
            macdSeries.add(ema12 - ema26);
        }

        // Signal line = EMA(9) of MACD series
        double macdLine = macdSeries.get(macdSeries.size() - 1);
        Double signal = calculateEMA(macdSeries, 9);

        if (signal == null) {
            return new double[]{macdLine, Double.NaN, Double.NaN};
        }

        double histogram = macdLine - signal;
        return new double[]{macdLine, signal, histogram};
    }

    /**
     * Bollinger Bands (period, stdDevMultiplier).
     * Returns double[3] = {upper, middle, lower}.
     * Middle = SMA(period), Upper/Lower = middle +/- stdDev * multiplier.
     */
    public double[] calculateBollingerBands(List<Double> closes, int period, double stdDevMultiplier) {
        if (closes == null || closes.size() < period) {
            return null;
        }

        Double middle = calculateSMA(closes, period);
        if (middle == null) {
            return null;
        }

        // Standard deviation over the last 'period' values
        int start = closes.size() - period;
        double sumSquaredDiff = 0.0;
        for (int i = start; i < closes.size(); i++) {
            double diff = closes.get(i) - middle;
            sumSquaredDiff += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquaredDiff / period);

        double upper = middle + stdDevMultiplier * stdDev;
        double lower = middle - stdDevMultiplier * stdDev;

        return new double[]{upper, middle, lower};
    }

    /**
     * Average True Range over 'period'.
     * True Range = max(high-low, |high-prevClose|, |low-prevClose|).
     * ATR = Wilder's smoothed average of TR values.
     */
    public Double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (highs == null || lows == null || closes == null) {
            return null;
        }
        int size = Math.min(Math.min(highs.size(), lows.size()), closes.size());
        if (size <= period) {
            return null;
        }

        List<Double> trueRanges = new ArrayList<>();
        // First TR uses just high - low (no previous close)
        trueRanges.add(highs.get(0) - lows.get(0));

        for (int i = 1; i < size; i++) {
            double highLow = highs.get(i) - lows.get(i);
            double highPrevClose = Math.abs(highs.get(i) - closes.get(i - 1));
            double lowPrevClose = Math.abs(lows.get(i) - closes.get(i - 1));
            trueRanges.add(Math.max(highLow, Math.max(highPrevClose, lowPrevClose)));
        }

        // Initial ATR = SMA of first 'period' true ranges
        double atr = 0.0;
        for (int i = 0; i < period; i++) {
            atr += trueRanges.get(i);
        }
        atr /= period;

        // Wilder's smoothing for remaining values
        for (int i = period; i < trueRanges.size(); i++) {
            atr = (atr * (period - 1) + trueRanges.get(i)) / period;
        }

        return atr;
    }

    /**
     * Finds support and resistance levels using local minima/maxima.
     * Returns double[2] = {support, resistance}.
     * Scans recent data for swing lows (support) and swing highs (resistance)
     * using a 5-bar lookback window.
     */
    public double[] calculateSupportResistance(List<Double> highs, List<Double> lows, List<Double> closes) {
        if (highs == null || lows == null || closes == null) {
            return null;
        }
        int size = Math.min(Math.min(highs.size(), lows.size()), closes.size());
        if (size < 5) {
            return null;
        }

        int lookback = 2;
        double currentClose = closes.get(size - 1);
        double support = Double.MIN_VALUE;
        double resistance = Double.MAX_VALUE;

        // Find swing lows (support) and swing highs (resistance)
        for (int i = lookback; i < size - lookback; i++) {
            boolean isSwingLow = true;
            boolean isSwingHigh = true;

            for (int j = 1; j <= lookback; j++) {
                if (lows.get(i) >= lows.get(i - j) || lows.get(i) >= lows.get(i + j)) {
                    isSwingLow = false;
                }
                if (highs.get(i) <= highs.get(i - j) || highs.get(i) <= highs.get(i + j)) {
                    isSwingHigh = false;
                }
            }

            // Support: highest swing low below current price
            if (isSwingLow && lows.get(i) < currentClose && lows.get(i) > support) {
                support = lows.get(i);
            }

            // Resistance: lowest swing high above current price
            if (isSwingHigh && highs.get(i) > currentClose && highs.get(i) < resistance) {
                resistance = highs.get(i);
            }
        }

        // Fallback if no swing points found
        if (support == Double.MIN_VALUE) {
            support = findRecentMin(lows, Math.max(0, size - 50), size);
        }
        if (resistance == Double.MAX_VALUE) {
            resistance = findRecentMax(highs, Math.max(0, size - 50), size);
        }

        return new double[]{support, resistance};
    }

    private double findRecentMin(List<Double> values, int from, int to) {
        double min = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            if (values.get(i) < min) {
                min = values.get(i);
            }
        }
        return min;
    }

    private double findRecentMax(List<Double> values, int from, int to) {
        double max = Double.MIN_VALUE;
        for (int i = from; i < to; i++) {
            if (values.get(i) > max) {
                max = values.get(i);
            }
        }
        return max;
    }
}
