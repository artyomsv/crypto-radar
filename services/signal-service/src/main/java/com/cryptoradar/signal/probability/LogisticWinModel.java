package com.cryptoradar.signal.probability;

/**
 * A small logistic-regression win-probability model over the six dimension
 * scores. Pure (no framework, no I/O) so the math is unit-testable; the CDI
 * layer feeds it real closed-outcome data and serves predictions.
 *
 * <p>Features are the raw dimension scores (roughly −100..+100); they are scaled
 * by {@link #FEATURE_SCALE} internally so a single learning rate behaves across
 * all six. Until {@link #train} has run on real data the model is "untrained"
 * and {@link #predict} returns {@code 0.5} (no information), so a cold start can
 * never masquerade as a confident probability.
 */
public final class LogisticWinModel {

    public static final int FEATURES = 6;
    private static final double FEATURE_SCALE = 100.0;

    private final double[] weights = new double[FEATURES];
    private double bias = 0.0;
    private volatile boolean trained = false;

    public boolean isTrained() {
        return trained;
    }

    static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** P(win) for a feature row, or 0.5 if the model has not been trained yet. */
    public double predict(double[] features) {
        if (!trained) return 0.5;
        return sigmoid(logit(features));
    }

    private double logit(double[] features) {
        double z = bias;
        for (int i = 0; i < FEATURES; i++) {
            z += weights[i] * (features[i] / FEATURE_SCALE);
        }
        return z;
    }

    /**
     * Batch gradient descent with L2 regularization. {@code X[i]} is a 6-length
     * raw-score row, {@code y[i]} is 1 (win) or 0 (loss). No-op if the dataset is
     * empty, leaving the model untrained.
     */
    public void train(double[][] X, int[] y, int epochs, double learningRate, double l2) {
        if (X.length == 0 || X.length != y.length) return;
        double[] w = new double[FEATURES];
        double b = 0.0;
        int n = X.length;
        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] gradW = new double[FEATURES];
            double gradB = 0.0;
            for (int i = 0; i < n; i++) {
                double pred = sigmoid(dot(w, b, X[i]));
                double error = pred - y[i];
                for (int j = 0; j < FEATURES; j++) {
                    gradW[j] += error * (X[i][j] / FEATURE_SCALE);
                }
                gradB += error;
            }
            for (int j = 0; j < FEATURES; j++) {
                w[j] -= learningRate * (gradW[j] / n + l2 * w[j]);
            }
            b -= learningRate * (gradB / n);
        }
        System.arraycopy(w, 0, weights, 0, FEATURES);
        this.bias = b;
        this.trained = true;
    }

    private static double dot(double[] w, double b, double[] x) {
        double z = b;
        for (int i = 0; i < FEATURES; i++) {
            z += w[i] * (x[i] / FEATURE_SCALE);
        }
        return z;
    }
}
