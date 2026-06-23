package com.cryptoradar.signal.probability;

/**
 * Win-probability model over the six dimension scores (≈−100..+100). Thin wrapper
 * over {@link LogisticRegression} with a feature scale of 100 so the raw scores
 * train well; the generic core holds the math. Untrained → {@link #predict}
 * returns 0.5 so a cold start can never masquerade as a confident probability.
 */
public final class LogisticWinModel {

    public static final int FEATURES = 6;
    private static final double FEATURE_SCALE = 100.0;

    private final LogisticRegression core = new LogisticRegression(FEATURES, FEATURE_SCALE);

    public boolean isTrained() {
        return core.isTrained();
    }

    static double sigmoid(double z) {
        return LogisticRegression.sigmoid(z);
    }

    public double predict(double[] features) {
        return core.predict(features);
    }

    public void train(double[][] X, int[] y, int epochs, double learningRate, double l2) {
        core.train(X, y, epochs, learningRate, l2);
    }
}
