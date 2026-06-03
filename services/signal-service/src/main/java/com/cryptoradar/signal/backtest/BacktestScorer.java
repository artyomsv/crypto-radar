package com.cryptoradar.signal.backtest;

import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.model.DimensionScore;

import java.util.List;

/**
 * Parameterized re-implementation of {@code SignalEngine}'s
 * {@code computeAlignment} and {@code determineSignalLabel} methods.
 *
 * <p><strong>This class intentionally duplicates logic from SignalEngine.</strong>
 * The duplication exists because SignalEngine currently hardcodes its thresholds
 * as private constants and cannot be parameterized externally. Once Agent C
 * refactors SignalEngine to read from {@link SignalConfig}, this class should be
 * deleted and callers redirected to the refactored engine helper.
 *
 * <p>TODO (Agent C): delete BacktestScorer once SignalEngine exposes
 * {@code computeAlignment(dims, score, config)} and
 * {@code determineSignalLabel(score, alignment, regime, config)} as package-private
 * statics or a shared utility.
 */
class BacktestScorer {

    static final String NEUTRAL = "NEUTRAL";
    private static final String STRONG_BUY = "STRONG_BUY";
    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final String STRONG_SELL = "STRONG_SELL";

    private BacktestScorer() {}

    /**
     * Computes a weighted overall score from per-dimension stored scores.
     * Null dimension scores contribute 0 to the weighted sum.
     */
    static double computeOverallScore(
            Double technical, Double whale, Double derivatives,
            Double sentiment, Double orderbook, Double macro,
            SignalConfig.Weights weights) {
        double score = zeroIfNull(technical) * weights.technical()
                + zeroIfNull(whale) * weights.whale()
                + zeroIfNull(derivatives) * weights.derivatives()
                + zeroIfNull(sentiment) * weights.sentiment()
                + zeroIfNull(orderbook) * weights.orderBook()
                + zeroIfNull(macro) * weights.macro();
        return clamp(score, -100, 100);
    }

    /**
     * Replicates {@code SignalEngine.computeAlignment}.
     *
     * <p>Mirrors the logic exactly — including the contradiction-count
     * penalty and the 0.9× output scale — but reads bounds from config
     * instead of hardcoded constants.
     */
    static int computeAlignment(List<DimensionScore> dimensions,
                                 double overallScore,
                                 SignalConfig.Alignment cfg) {
        if (Math.abs(overallScore) < cfg.minScoreForNonZero()) {
            return cfg.minOutput();
        }

        boolean isPositive = overallScore > 0;
        double weightedStrength = 0;
        double totalWeight = 0;
        int contradictions = 0;

        for (DimensionScore dim : dimensions) {
            double w = dim.weight();
            totalWeight += w;
            double s = dim.score();
            boolean aligned = isPositive ? s > 0 : s < 0;
            double absScore = Math.abs(s);

            if (aligned) {
                weightedStrength += (absScore / 100.0) * w;
            } else if (absScore >= cfg.contradictionScoreThreshold()) {
                contradictions++;
                weightedStrength -= (absScore / 100.0) * w * cfg.contradictionPenaltyMultiplier();
            }
        }

        double raw = totalWeight > 0 ? (weightedStrength / totalWeight) * 100 : 0;

        if (contradictions >= 2) raw *= cfg.twoContradictionPenalty();
        else if (contradictions >= 1) raw *= cfg.oneContradictionPenalty();

        int alignment = (int) (raw * cfg.outputScale());
        return Math.max(cfg.minOutput(), Math.min(cfg.maxOutput(), alignment));
    }

    /**
     * Replicates {@code SignalEngine.determineSignalLabel} with regime defaulting
     * to CHOP/UNKNOWN thresholds. Backtest outcomes don't carry stored regime so
     * we apply the base (CHOP) thresholds from the proposed config.
     */
    static String determineSignalLabel(double score, int alignment, SignalConfig.SignalLabels labels) {
        SignalConfig.RegimeThresholds thresholds = labels.chop();
        if (score >= thresholds.strongBuyMinScore() && alignment >= thresholds.strongAlignmentMin()) {
            return STRONG_BUY;
        }
        if (score >= thresholds.buyMinScore() && alignment >= thresholds.alignmentMin()) {
            return BUY;
        }
        if (score <= thresholds.strongSellMaxScore() && alignment >= thresholds.strongAlignmentMin()) {
            return STRONG_SELL;
        }
        if (score <= thresholds.sellMaxScore() && alignment >= thresholds.alignmentMin()) {
            return SELL;
        }
        return NEUTRAL;
    }

    private static double zeroIfNull(Double value) {
        return value == null ? 0.0 : value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
