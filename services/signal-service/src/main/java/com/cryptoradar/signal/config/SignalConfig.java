package com.cryptoradar.signal.config;

/**
 * Immutable runtime configuration for the signal engine.
 *
 * <p>Every magic number that previously lived as a static final in
 * {@code SignalEngine.java} now lives here. {@link com.cryptoradar.signal.service.SignalEngine}
 * reads the active config on each {@code computeSignal} call, so changes
 * propagate within one scheduling tick.
 *
 * <p>Versioned and persisted in the {@code signal_config_versions} table.
 * Validation (weights summing to 1.0, monotonic thresholds) is enforced at
 * save time by {@code ConfigService.validate}.
 */
public record SignalConfig(
        Weights weights,
        TradeLevels tradeLevels,
        Rsi rsi,
        Macd macd,
        Sma200 sma200,
        Bollinger bollinger,
        VolumeConfirmation volumeConfirmation,
        SupportResistance supportResistance,
        Whale whale,
        DerivativesFunding derivativesFunding,
        LongShortRatio longShortRatio,
        FearGreed fearGreed,
        NewsSentiment newsSentiment,
        OrderBook orderBook,
        MacroBtcDominance macroBtcDominance,
        Alignment alignment,
        SignalLabels signalLabels,
        Trail trail
) {

    public record Weights(
            double technical,
            double whale,
            double orderBook,
            double derivatives,
            double sentiment,
            double macro
    ) {
        public double sum() {
            return technical + whale + orderBook + derivatives + sentiment + macro;
        }
    }

    public record TradeLevels(
            double minRiskPct,
            double minRr,
            double atrStopMultiple,
            double supportStopAtrBuffer
    ) {}

    public record Rsi(
            double oversoldExtreme,
            double oversoldApproaching,
            double overboughtApproaching,
            double overboughtExtreme,
            double scoreOversoldExtreme,
            double scoreOversoldApproaching,
            double scoreOverboughtApproaching,
            double scoreOverboughtExtreme
    ) {}

    public record Macd(double scoreBullish, double scoreBearish) {}

    public record Sma200(double scoreAbove, double scoreBelow) {}

    public record Bollinger(
            double lowerPosition,
            double upperPosition,
            double scoreLower,
            double scoreUpper
    ) {}

    public record VolumeConfirmation(double scoreDecreasing, double scoreIncreasing) {}

    public record SupportResistance(
            double lowerPosition,
            double upperPosition,
            double scoreNearSupport,
            double scoreNearResistance
    ) {}

    public record Whale(int minSampleSize, int amplifyThreshold, double amplifyFactor) {}

    public record DerivativesFunding(
            double neutralThreshold,
            double moderateThreshold,
            double extremeThreshold,
            double scoreModerate,
            double scoreStrong,
            double scoreExtreme
    ) {}

    public record LongShortRatio(
            double extremelyCrowdedShortsPct,
            double crowdedShortsPct,
            double crowdedLongsPct,
            double extremelyCrowdedLongsPct,
            double scoreModerate,
            double scoreExtreme
    ) {}

    public record FearGreed(
            int extremeFearMax,
            int fearMax,
            int greedMin,
            int extremeGreedMin,
            double scoreModerate,
            double scoreExtreme
    ) {}

    public record NewsSentiment(double scoreMultiplier) {}

    public record OrderBook(
            double highLiquidationRatio,
            double moderateLiquidationRatio,
            double scoreHighVolatility
    ) {}

    public record MacroBtcDominance(
            double threshold,
            double scoreBtcWhenHigh,
            double scoreAltWhenHigh,
            double scoreBtcWhenLow,
            double scoreAltWhenLow
    ) {}

    public record Alignment(
            double minScoreForNonZero,
            double contradictionScoreThreshold,
            double contradictionPenaltyMultiplier,
            double twoContradictionPenalty,
            double oneContradictionPenalty,
            double outputScale,
            int minOutput,
            int maxOutput
    ) {}

    public record SignalLabels(
            RegimeThresholds chop,
            BullOverrides bull,
            BearOverrides bear
    ) {}

    public record RegimeThresholds(
            double strongBuyMinScore,
            double buyMinScore,
            double strongSellMaxScore,
            double sellMaxScore,
            int strongAlignmentMin,
            int alignmentMin
    ) {}

    public record BullOverrides(
            double strongSellMaxScore,
            double sellMaxScore
    ) {}

    public record BearOverrides(
            double strongBuyMinScore,
            double buyMinScore
    ) {}

    /**
     * Trailing-stop ladder parameters in R-units. Mirrors the shape of
     * {@code com.cryptoradar.core.TrailConfig} so callers can convert directly.
     * Per-strategy detector overrides still flow through {@code TradeSetup};
     * this group is the engine default for outcomes lacking an explicit
     * config (dimension-scoring path) and the global wider-offset rung used
     * by every trail evaluation.
     */
    public record Trail(
            double activationR,
            double stepR,
            double offsetR,
            double widerOffsetActivationR,
            double widerOffsetR
    ) {}

    /**
     * Returns the canonical default config, matching the v1 seed in
     * {@code db/init/signal-config-init.sql}. Used as a test fixture and as
     * the "Reset to defaults" target in the UI.
     */
    public static SignalConfig defaults() {
        return new SignalConfig(
                new Weights(0.35, 0.20, 0.10, 0.15, 0.10, 0.10),
                new TradeLevels(0.015, 2.0, 1.5, 0.5),
                new Rsi(30, 40, 60, 70, 80, 40, -40, -80),
                new Macd(30, -30),
                new Sma200(35, -35),
                new Bollinger(0.3, 0.7, 20, -20),
                new VolumeConfirmation(-10, 5),
                new SupportResistance(0.3, 0.7, 30, -30),
                new Whale(15, 30, 1.20),
                new DerivativesFunding(0.0003, 0.0008, 0.0015, 20, 35, 50),
                new LongShortRatio(30, 40, 60, 70, 20, 35),
                new FearGreed(10, 25, 75, 90, 15, 30),
                new NewsSentiment(50),
                new OrderBook(0.05, 0.01, -20),
                new MacroBtcDominance(50.0, 20, -20, -10, 20),
                new Alignment(5, 30, 0.5, 0.6, 0.8, 0.9, 15, 90),
                new SignalLabels(
                        new RegimeThresholds(55, 25, -40, -15, 60, 35),
                        new BullOverrides(-55, -30),
                        new BearOverrides(70, 40)
                ),
                new Trail(1.0, 0.5, 0.5, 2.5, 1.0)
        );
    }
}
