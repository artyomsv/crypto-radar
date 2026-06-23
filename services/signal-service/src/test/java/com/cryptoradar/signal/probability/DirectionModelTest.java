package com.cryptoradar.signal.probability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectionModelTest {

    private static final double EPS = 1e-9;

    @Test
    void untrainedReturnsHalf() {
        DirectionModel model = new DirectionModel();
        assertFalse(model.isTrained());
        assertEquals(0.5, model.longWinProbability(new double[DirectionModel.FEATURES]), EPS);
    }

    @Test
    void toVectorPreservesIndicatorOrder() {
        TechnicalIndicators ind = new TechnicalIndicators(70.0, 0.9, 1.5, 0.05, 2.0, 1.3);
        double[] v = DirectionModel.toVector(ind);
        assertArrayEquals(new double[]{70.0, 0.9, 1.5, 0.05, 2.0, 1.3}, v, EPS);
    }

    @Test
    void trainsAcrossWildlyDifferentFeatureScales() {
        // Feature 0 (RSI-like 0..100) separates; feature 3 (momentum-like ~0.01)
        // is noise. Standardization must let the big-scale signal train.
        double[][] x = {
                {80, 0.5, 0, 0.001, 1, 1}, {75, 0.5, 0, -0.001, 1, 1}, {90, 0.5, 0, 0.002, 1, 1},
                {20, 0.5, 0, 0.001, 1, 1}, {25, 0.5, 0, -0.001, 1, 1}, {10, 0.5, 0, 0.002, 1, 1}
        };
        int[] y = {1, 1, 1, 0, 0, 0};
        DirectionModel model = new DirectionModel();
        model.train(x, y, 4000, 0.5, 0.0);
        assertTrue(model.isTrained());
        assertTrue(model.longWinProbability(new double[]{85, 0.5, 0, 0.0, 1, 1}) > 0.5);
        assertTrue(model.longWinProbability(new double[]{15, 0.5, 0, 0.0, 1, 1}) < 0.5);
    }

    @Test
    void trainIsNoOpOnEmptyData() {
        DirectionModel model = new DirectionModel();
        model.train(new double[0][], new int[0], 100, 0.3, 0.0);
        assertFalse(model.isTrained());
    }
}
