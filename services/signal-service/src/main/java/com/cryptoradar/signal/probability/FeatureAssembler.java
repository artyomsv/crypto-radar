package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.TradingSignal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the full feature snapshot persisted with each shadow candidate: the
 * six dimension-score aggregates (what the Phase 1 logistic model trains on) PLUS
 * the raw instruments and cross-venue signals a richer Phase 2 model can learn
 * from — candle-derived indicators (RSI/Bollinger/MACD/momentum/realized-vol/
 * volume) and multi-venue liquidation imbalance.
 *
 * <p>Logging these now, during shadow, is what makes Phase 2 possible: the raw
 * features only accumulate labelled history going forward, so every scan that
 * omitted them would be a row the rich model can never use.
 */
@ApplicationScoped
public class FeatureAssembler {

    @Inject
    LiquidationImbalanceReader liquidationReader;

    public Map<String, Object> assemble(TradingSignal signal, Candidate candidate,
                                        List<CandleBar> bars, Map<String, Double> dimensionScores) {
        Map<String, Object> features = new LinkedHashMap<>(dimensionScores);
        features.put("overallScore", signal.getOverallScore());
        features.put("alignment", signal.getAlignment());
        features.put("atr", candidate.atr());
        features.put("atrPct", candidate.atr() / candidate.entry());

        TechnicalIndicators ind = TechnicalIndicators.compute(bars);
        if (ind != null) {
            features.put("rsi14", ind.rsi14());
            features.put("bollingerPercentB", ind.bollingerPercentB());
            features.put("macdHistogram", ind.macdHistogram());
            features.put("momentum10", ind.momentum10());
            features.put("realizedVolPct", ind.realizedVolPct());
            features.put("volumeRatio", ind.volumeRatio());
        }

        Double liqImbalance = liquidationReader.imbalance24h(signal.getSymbol());
        if (liqImbalance != null) {
            features.put("liqImbalance24h", liqImbalance);
        }
        return features;
    }
}
