package com.cryptoradar.signal.probability;

/**
 * Width-parameterized logistic regression with L2-regularized batch gradient
 * descent. Pure (no framework, no I/O) so the math is unit-testable. Features are
 * divided by {@code featureScale} internally so one learning rate behaves across
 * inputs of similar magnitude; pass {@code 1.0} when callers pre-standardize.
 * Until {@link #train} runs on a non-empty dataset the model is untrained and
 * {@link #predict} returns {@code 0.5} (no information).
 */
public final class LogisticRegression {

    private final int features;
    private final double featureScale;
    private final double[] weights;
    private double bias = 0.0;
    private volatile boolean trained = false;

    public LogisticRegression(int features, double featureScale) {
        if (features <= 0) throw new IllegalArgumentException("features must be positive: " + features);
        if (featureScale == 0 || Double.isNaN(featureScale)) throw new IllegalArgumentException("featureScale must be non-zero or NaN: " + featureScale);
        this.features = features;
        this.featureScale = featureScale;
        this.weights = new double[features];
    }

    public boolean isTrained() {
        return trained;
    }

    static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public double predict(double[] x) {
        if (x.length < features) return 0.5;
        if (!trained) return 0.5;
        return sigmoid(dot(weights, bias, x));
    }

    public void train(double[][] X, int[] y, int epochs, double learningRate, double l2) {
        if (X.length == 0 || X.length != y.length) return;
        if (X[0].length < features) return;
        double[] w = new double[features];
        double b = 0.0;
        int n = X.length;
        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] gradW = new double[features];
            double gradB = 0.0;
            for (int i = 0; i < n; i++) {
                double error = sigmoid(dot(w, b, X[i])) - y[i];
                for (int j = 0; j < features; j++) {
                    gradW[j] += error * (X[i][j] / featureScale);
                }
                gradB += error;
            }
            for (int j = 0; j < features; j++) {
                w[j] -= learningRate * (gradW[j] / n + l2 * w[j]);
            }
            b -= learningRate * (gradB / n);
        }
        System.arraycopy(w, 0, weights, 0, features);
        this.bias = b;
        this.trained = true;
    }

    private double dot(double[] w, double b, double[] x) {
        double z = b;
        for (int i = 0; i < features; i++) {
            z += w[i] * (x[i] / featureScale);
        }
        return z;
    }
}
