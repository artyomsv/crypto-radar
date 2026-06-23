package com.cryptoradar.signal.probability;

/**
 * Predicts P(a LONG 1:1 trade hits its target before its stop) from the six
 * candle-derived {@link TechnicalIndicators}. Because those features live on
 * wildly different scales (RSI 0..100, %B ~0..1, momentum ~0.01), this owns
 * per-feature z-score standardization fitted at train time and reused at predict
 * time, then defers the fit to a {@link LogisticRegression}. Pure — no I/O — so
 * the training math is unit-testable; the CDI trainer feeds it real history.
 *
 * <p>Untrained → {@link #longWinProbability} returns 0.5 (no information), so the
 * generator that consumes it can skip rather than guess a direction.
 */
public final class DirectionModel {

    public static final int FEATURES = 6;

    private final LogisticRegression core = new LogisticRegression(FEATURES, 1.0);
    private volatile double[] mean;
    private volatile double[] std;

    public boolean isTrained() {
        return core.isTrained();
    }

    /** Fixed feature order — must match the trainer and any caller building a row. */
    public static double[] toVector(TechnicalIndicators ind) {
        return new double[]{
                ind.rsi14(), ind.bollingerPercentB(), ind.macdHistogram(),
                ind.momentum10(), ind.realizedVolPct(), ind.volumeRatio()
        };
    }

    public void train(double[][] rawRows, int[] labels, int epochs, double learningRate, double l2) {
        if (rawRows.length == 0 || rawRows.length != labels.length) return;
        double[] m = new double[FEATURES];
        double[] s = new double[FEATURES];
        for (double[] row : rawRows) {
            for (int j = 0; j < FEATURES; j++) m[j] += row[j];
        }
        for (int j = 0; j < FEATURES; j++) m[j] /= rawRows.length;
        for (double[] row : rawRows) {
            for (int j = 0; j < FEATURES; j++) {
                double d = row[j] - m[j];
                s[j] += d * d;
            }
        }
        for (int j = 0; j < FEATURES; j++) {
            s[j] = Math.sqrt(s[j] / rawRows.length);
            if (s[j] == 0) s[j] = 1.0; // constant feature → no scaling, avoids div-by-zero
        }
        double[][] standardized = new double[rawRows.length][FEATURES];
        for (int i = 0; i < rawRows.length; i++) {
            standardized[i] = standardize(rawRows[i], m, s);
        }
        this.mean = m;
        this.std = s;
        core.train(standardized, labels, epochs, learningRate, l2);
    }

    public double longWinProbability(double[] rawFeatures) {
        if (!core.isTrained()) return 0.5;
        return core.predict(standardize(rawFeatures, mean, std));
    }

    private static double[] standardize(double[] raw, double[] mean, double[] std) {
        double[] out = new double[FEATURES];
        for (int j = 0; j < FEATURES; j++) {
            out[j] = (raw[j] - mean[j]) / std[j];
        }
        return out;
    }
}
