package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.TradingSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {

    private static final double EPS = 1e-9;

    private static DirectionContext ctx(double overallScore) {
        TradingSignal signal = new TradingSignal();
        signal.setSymbol("BTCUSDT");
        signal.setOverallScore(overallScore);
        TechnicalIndicators ind = new TechnicalIndicators(60, 0.6, 0.2, 0.01, 1.0, 1.1);
        return new DirectionContext(signal, List.<CandleBar>of(), 4.0, 100.0, ind, Map.of());
    }

    @Test
    void flipInvertsBullishOverallScoreToShort() {
        FlipGenerator gen = new FlipGenerator();
        gen.invertDirection = true;
        gen.stopAtrMult = 1.5;
        gen.targetR = 1.0;
        gen.tag = "v2-1to1-flip";
        gen.enabled = true;
        gen.runLlm = true;

        Optional<Candidate> c = gen.build(ctx(50.0)); // bullish → inverted → SHORT
        assertTrue(c.isPresent());
        assertEquals(Candidate.SHORT, c.get().direction());
        // risk = max(1.5*4, 0.015*100) = 6 → SHORT stop above, target below at 1:1
        assertEquals(106.0, c.get().stop(), EPS);
        assertEquals(94.0, c.get().target(), EPS);
        assertEquals("v2-1to1-flip", gen.tag());
    }

    @Test
    void featureDirGeneratorSkipsWhenModelUntrained() {
        FeatureDirectionGenerator gen = new FeatureDirectionGenerator();
        gen.trainer = new DirectionModelTrainer(); // model().isTrained() == false
        gen.stopAtrMult = 1.5;
        gen.targetR = 1.0;
        gen.tag = "v3-feature-dir";
        gen.enabled = true;
        gen.runLlm = true;
        assertTrue(gen.build(ctx(50.0)).isEmpty());
    }

    @Test
    void featureDirGeneratorSkipsWhenIndicatorsNull() {
        FeatureDirectionGenerator gen = new FeatureDirectionGenerator();
        gen.trainer = new DirectionModelTrainer();
        gen.stopAtrMult = 1.5;
        gen.targetR = 1.0;
        gen.tag = "v3-feature-dir";
        gen.enabled = true;
        gen.runLlm = true;
        TradingSignal s = new TradingSignal();
        s.setSymbol("BTCUSDT");
        s.setOverallScore(10);
        DirectionContext noInd = new DirectionContext(s, List.<CandleBar>of(), 4.0, 100.0, null, Map.of());
        assertTrue(gen.build(noInd).isEmpty());
    }
}
