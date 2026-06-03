package com.cryptoradar.signal.service;

import com.cryptoradar.signal.config.ConfigService;
import com.cryptoradar.signal.config.SignalConfig;
import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.TradingSignal;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core multi-dimensional scoring algorithm.
 * Each dimension produces a score from -100 to +100, combined with weights
 * to produce an overall trading signal with an alignment score.
 *
 * <p>All numeric constants are read from the active {@link SignalConfig} on
 * each {@code computeSignal} call, enabling hot-reload without service restart.
 */
@ApplicationScoped
public class SignalEngine {

    private static final Logger LOG = Logger.getLogger(SignalEngine.class);

    private static final String STRONG_BUY  = "STRONG_BUY";
    private static final String BUY         = "BUY";
    private static final String NEUTRAL     = "NEUTRAL";
    private static final String SELL        = "SELL";
    private static final String STRONG_SELL = "STRONG_SELL";

    private static final double WEIGHT_SUM_WARN_TOLERANCE = 0.01;

    private final ConfigService configService;

    public SignalEngine(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Regime-agnostic overload — callers that don't have regime context (such
     * as unit tests) fall through to this and are treated as {@link
     * com.cryptoradar.signal.model.MarketRegime#CHOP}.
     */
    public TradingSignal computeSignal(String symbol,
                                       Map<String, Object> analytics,
                                       Map<String, Object> whaleData,
                                       Map<String, Object> derivativesData,
                                       Map<String, Object> priceData,
                                       Map<String, Object> macroData) {
        return computeSignal(symbol, analytics, whaleData, derivativesData, priceData, macroData,
                com.cryptoradar.signal.model.MarketRegime.CHOP);
    }

    public TradingSignal computeSignal(String symbol,
                                       Map<String, Object> analytics,
                                       Map<String, Object> whaleData,
                                       Map<String, Object> derivativesData,
                                       Map<String, Object> priceData,
                                       Map<String, Object> macroData,
                                       com.cryptoradar.signal.model.MarketRegime regime) {
        SignalConfig config = resolveConfig();
        warnIfWeightSumOff(config.weights());

        DimensionScore technical   = scoreTechnical(analytics, config);
        DimensionScore whale       = scoreWhale(whaleData, config.whale(), config.weights().whale());
        DimensionScore derivatives = scoreDerivatives(derivativesData, config);
        DimensionScore sentiment   = scoreSentiment(analytics, macroData, config);
        DimensionScore orderBook   = scoreOrderBook(derivativesData, config.orderBook(), config.weights().orderBook());
        DimensionScore macro       = scoreMacro(macroData, symbol, config.macroBtcDominance(), config.weights().macro());

        List<DimensionScore> dimensions = List.of(
                technical, whale, derivatives, sentiment, orderBook, macro);

        double overallScore = computeOverallScore(dimensions, config.weights());
        int alignment = computeAlignment(dimensions, overallScore, config.alignment());
        String signalLabel = determineSignalLabel(overallScore, alignment, regime, config.signalLabels());

        TradingSignal signal = new TradingSignal();
        signal.setSymbol(symbol);
        signal.setTimestamp(Instant.now());
        signal.setSignal(signalLabel);
        signal.setOverallScore(Math.round(overallScore * 100.0) / 100.0);
        signal.setAlignment(alignment);
        signal.setDimensions(new ArrayList<>(dimensions));
        signal.setAlertLevel(determineAlertLevel(signalLabel, alignment, config.signalLabels().chop()));

        populateTradeLevels(signal, analytics, priceData, config.tradeLevels());

        return signal;
    }

    // --- Dimension scoring ---

    private DimensionScore scoreTechnical(Map<String, Object> analytics, SignalConfig config) {
        if (analytics == null) {
            return new DimensionScore("Technical", 0, config.weights().technical(),
                    List.of("No analytics data available"));
        }

        List<String> reasons = new ArrayList<>();
        Map<String, Object> indicators = asMap(analytics.get("technicalIndicators"));

        double score = computeRsiScore(indicators, reasons, config.rsi())
                + computeMacdScore(indicators, reasons, config.macd())
                + computeSma200Score(analytics, indicators, reasons, config.sma200())
                + computeSupportResistanceScore(analytics, reasons, config.supportResistance())
                + computeBollingerScore(indicators, reasons, config.bollinger())
                + computeVolumeConfirmation(analytics, reasons, config.volumeConfirmation());

        return new DimensionScore("Technical", clamp(score, -100, 100),
                config.weights().technical(), reasons);
    }

    private double computeRsiScore(Map<String, Object> indicators, List<String> reasons,
                                   SignalConfig.Rsi rsi) {
        Double rsiVal = asDouble(indicators != null ? indicators.get("rsi14") : null);
        if (rsiVal == null) return 0;

        if (rsiVal < rsi.oversoldExtreme()) {
            reasons.add(String.format("RSI at %.1f — oversold (bullish)", rsiVal));
            return rsi.scoreOversoldExtreme();
        }
        if (rsiVal < rsi.oversoldApproaching()) {
            reasons.add(String.format("RSI at %.1f — approaching oversold", rsiVal));
            return rsi.scoreOversoldApproaching();
        }
        if (rsiVal > rsi.overboughtExtreme()) {
            reasons.add(String.format("RSI at %.1f — overbought (bearish)", rsiVal));
            return rsi.scoreOverboughtExtreme();
        }
        if (rsiVal > rsi.overboughtApproaching()) {
            reasons.add(String.format("RSI at %.1f — approaching overbought", rsiVal));
            return rsi.scoreOverboughtApproaching();
        }
        reasons.add(String.format("RSI at %.1f — neutral", rsiVal));
        return 0;
    }

    private double computeMacdScore(Map<String, Object> indicators, List<String> reasons,
                                    SignalConfig.Macd macd) {
        Double macdHistogram = asDouble(indicators != null ? indicators.get("macdHistogram") : null);
        if (macdHistogram == null) return 0;

        if (macdHistogram > 0) {
            reasons.add("MACD histogram positive (bullish momentum)");
            return macd.scoreBullish();
        }
        reasons.add("MACD histogram negative (bearish momentum)");
        return macd.scoreBearish();
    }

    private double computeSma200Score(Map<String, Object> analytics, Map<String, Object> indicators,
                                      List<String> reasons, SignalConfig.Sma200 sma200) {
        Double sma200Val = asDouble(indicators != null ? indicators.get("sma200") : null);
        if (sma200Val == null || sma200Val <= 0) return 0;

        Double support    = asDouble(analytics.get("supportLevel"));
        Double resistance = asDouble(analytics.get("resistanceLevel"));
        if (support == null || resistance == null || support <= 0 || resistance <= 0) return 0;

        double midPrice = (support + resistance) / 2;
        if (midPrice > sma200Val) {
            reasons.add("Price above SMA200 (bullish macro trend)");
            return sma200.scoreAbove();
        }
        reasons.add("Price below SMA200 (bearish macro trend — major headwind)");
        return sma200.scoreBelow();
    }

    private double computeSupportResistanceScore(Map<String, Object> analytics, List<String> reasons,
                                                 SignalConfig.SupportResistance sr) {
        Double support    = asDouble(analytics.get("supportLevel"));
        Double resistance = asDouble(analytics.get("resistanceLevel"));
        if (support == null || resistance == null || support <= 0 || resistance <= 0) return 0;
        if (resistance - support <= 0) return 0;

        Double overallScore = asDouble(analytics.get("overallScore"));
        if (overallScore == null) return 0;

        double position = overallScore / 100.0;
        if (position < sr.lowerPosition()) {
            reasons.add(String.format("Near support %.2f (potential bounce)", support));
            return sr.scoreNearSupport();
        }
        if (position > sr.upperPosition()) {
            reasons.add(String.format("Near resistance %.2f (potential rejection)", resistance));
            return sr.scoreNearResistance();
        }
        return 0;
    }

    private double computeVolumeConfirmation(Map<String, Object> analytics, List<String> reasons,
                                             SignalConfig.VolumeConfirmation vc) {
        Object volumeTrend = analytics.get("volumeTrend");
        String trend = volumeTrend != null ? volumeTrend.toString() : null;
        if ("DECREASING".equals(trend)) {
            reasons.add("Volume declining — weakens directional conviction");
            return vc.scoreDecreasing();
        }
        if ("INCREASING".equals(trend)) {
            reasons.add("Volume increasing — trend has conviction");
            return vc.scoreIncreasing();
        }
        return 0;
    }

    private double computeBollingerScore(Map<String, Object> indicators, List<String> reasons,
                                         SignalConfig.Bollinger bollinger) {
        if (indicators == null) return 0;

        Double bbUpper  = asDouble(indicators.get("bollingerUpper"));
        Double bbLower  = asDouble(indicators.get("bollingerLower"));
        Double bbMiddle = asDouble(indicators.get("bollingerMiddle"));

        if (bbUpper == null || bbLower == null || bbMiddle == null) return 0;
        if (bbUpper <= bbLower) return 0;

        double range    = bbUpper - bbLower;
        double position = (bbMiddle - bbLower) / range;

        if (position < bollinger.lowerPosition()) {
            reasons.add("Price near lower Bollinger band (potential reversal up)");
            return bollinger.scoreLower();
        }
        if (position > bollinger.upperPosition()) {
            reasons.add("Price near upper Bollinger band (potential reversal down)");
            return bollinger.scoreUpper();
        }
        return 0;
    }

    private DimensionScore scoreWhale(Map<String, Object> whaleData,
                                      SignalConfig.Whale whale, double weight) {
        if (whaleData == null) {
            return new DimensionScore("Whale", 0, weight, List.of("No whale data available"));
        }

        List<String> reasons = new ArrayList<>();
        Double pressure = asDouble(whaleData.get("whalePressure"));

        if (pressure == null) {
            return new DimensionScore("Whale", 0, weight, List.of("Whale pressure data unavailable"));
        }

        double score = clamp(pressure, -100, 100);
        reasons.add(String.format("Whale pressure %+.0f — %s", pressure,
                pressure > 30 ? "heavy buying" : pressure < -30 ? "heavy selling" : "balanced"));

        Integer tradeCount = asInt(whaleData.get("tradeCount1h"));
        if (tradeCount != null) {
            if (tradeCount < whale.minSampleSize()) {
                double dampening = tradeCount / (double) whale.minSampleSize();
                score *= dampening;
                reasons.add(String.format("%d whale trades in 1h — low sample, signal at %.0f%%",
                        tradeCount, dampening * 100));
            } else if (tradeCount >= whale.amplifyThreshold()) {
                score *= whale.amplifyFactor();
                reasons.add(String.format("%d whale trades in 1h — high activity, signal amplified", tradeCount));
            } else {
                reasons.add(String.format("%d whale trades in 1h — moderate activity", tradeCount));
            }
        }

        return new DimensionScore("Whale", clamp(score, -100, 100), weight, reasons);
    }

    private DimensionScore scoreDerivatives(Map<String, Object> derivativesData, SignalConfig config) {
        if (derivativesData == null) {
            return new DimensionScore("Derivatives", 0, config.weights().derivatives(),
                    List.of("No derivatives data available"));
        }

        double score = 0;
        List<String> reasons = new ArrayList<>();

        score += computeFundingScore(derivativesData, reasons, config.derivativesFunding());
        score += computeLongShortScore(derivativesData, reasons, config.longShortRatio());

        return new DimensionScore("Derivatives", clamp(score, -100, 100),
                config.weights().derivatives(), reasons);
    }

    private double computeFundingScore(Map<String, Object> derivativesData, List<String> reasons,
                                       SignalConfig.DerivativesFunding funding) {
        Double fundingRate = asDouble(derivativesData.get("fundingRate"));
        if (fundingRate == null) return 0;

        double fundingPct = fundingRate * 100;
        double absFunding = Math.abs(fundingRate);

        if (absFunding < funding.neutralThreshold()) {
            reasons.add(String.format("Funding rate %.4f%% — neutral range", fundingPct));
            return 0;
        }

        double fundingScore;
        if (absFunding >= funding.extremeThreshold()) {
            fundingScore = funding.scoreExtreme();
        } else if (absFunding >= funding.moderateThreshold()) {
            fundingScore = funding.scoreStrong();
        } else {
            fundingScore = funding.scoreModerate();
        }

        if (fundingRate < 0) {
            reasons.add(String.format("Funding rate %.4f%% — shorts overcrowded, %s contrarian bullish",
                    fundingPct, fundingScore >= funding.scoreStrong() ? "strongly" : "mildly"));
            return fundingScore;
        }
        reasons.add(String.format("Funding rate %.4f%% — longs overcrowded, %s contrarian bearish",
                fundingPct, fundingScore >= funding.scoreStrong() ? "strongly" : "mildly"));
        return -fundingScore;
    }

    private double computeLongShortScore(Map<String, Object> derivativesData, List<String> reasons,
                                         SignalConfig.LongShortRatio ls) {
        // Upstream returns a fraction in [0,1] (Bybit buyRatio); normalise to percent.
        Double longFraction = asDouble(derivativesData.get("longPct"));
        if (longFraction == null) return 0;

        double longPercent = longFraction > 1.0 ? longFraction : longFraction * 100.0;

        if (longPercent > ls.extremelyCrowdedLongsPct()) {
            reasons.add(String.format("%.0f%% long — extremely crowded (bearish)", longPercent));
            return -ls.scoreExtreme();
        }
        if (longPercent > ls.crowdedLongsPct()) {
            reasons.add(String.format("%.0f%% long — crowded longs (bearish)", longPercent));
            return -ls.scoreModerate();
        }
        if (longPercent < ls.extremelyCrowdedShortsPct()) {
            reasons.add(String.format("%.0f%% long — extremely crowded shorts (bullish)", longPercent));
            return ls.scoreExtreme();
        }
        if (longPercent < ls.crowdedShortsPct()) {
            reasons.add(String.format("%.0f%% long — crowded shorts (bullish)", longPercent));
            return ls.scoreModerate();
        }
        reasons.add(String.format("%.0f%% long — balanced positioning", longPercent));
        return 0;
    }

    private DimensionScore scoreSentiment(Map<String, Object> analytics, Map<String, Object> macroData,
                                          SignalConfig config) {
        double score = 0;
        List<String> reasons = new ArrayList<>();

        if (analytics != null) {
            Double sentimentScore = asDouble(analytics.get("sentimentScore"));
            if (sentimentScore != null) {
                score += sentimentScore * config.newsSentiment().scoreMultiplier();
                reasons.add(String.format("News sentiment %.2f — %s", sentimentScore,
                        sentimentScore > 0.3 ? "positive" : sentimentScore < -0.3 ? "negative" : "neutral"));
            }
        }

        if (macroData != null) {
            score += computeFearGreedScore(macroData, reasons, config.fearGreed());
        }

        if (reasons.isEmpty()) {
            reasons.add("No sentiment data available");
        }

        return new DimensionScore("Sentiment", clamp(score, -100, 100),
                config.weights().sentiment(), reasons);
    }

    private double computeFearGreedScore(Map<String, Object> macroData, List<String> reasons,
                                         SignalConfig.FearGreed fg) {
        Integer fearGreed = asInt(macroData.get("fearGreedIndex"));
        if (fearGreed == null) return 0;

        if (fearGreed <= fg.extremeFearMax()) {
            reasons.add(String.format("Fear & Greed at %d — extreme fear (strong contrarian buy)", fearGreed));
            return fg.scoreExtreme();
        }
        if (fearGreed <= fg.fearMax()) {
            reasons.add(String.format("Fear & Greed at %d — fear (mild contrarian buy)", fearGreed));
            return fg.scoreModerate();
        }
        if (fearGreed >= fg.extremeGreedMin()) {
            reasons.add(String.format("Fear & Greed at %d — extreme greed (strong contrarian sell)", fearGreed));
            return -fg.scoreExtreme();
        }
        if (fearGreed >= fg.greedMin()) {
            reasons.add(String.format("Fear & Greed at %d — greed (mild contrarian sell)", fearGreed));
            return -fg.scoreModerate();
        }
        reasons.add(String.format("Fear & Greed at %d — neutral zone", fearGreed));
        return 0;
    }

    private DimensionScore scoreOrderBook(Map<String, Object> derivativesData,
                                          SignalConfig.OrderBook ob, double weight) {
        if (derivativesData == null) {
            return new DimensionScore("Order Book", 0, weight, List.of("No order book data available"));
        }

        double score = 0;
        List<String> reasons = new ArrayList<>();

        Double liquidations24h = asDouble(derivativesData.get("liquidations24hUsd"));
        Double oiUsd           = asDouble(derivativesData.get("openInterestUsd"));

        if (liquidations24h != null && oiUsd != null && oiUsd > 0) {
            double liquidationRatio = liquidations24h / oiUsd;
            if (liquidationRatio > ob.highLiquidationRatio()) {
                score += ob.scoreHighVolatility();
                reasons.add(String.format("High liquidation activity (%.1f%% of OI) — volatile, risky",
                        liquidationRatio * 100));
            } else if (liquidationRatio > ob.moderateLiquidationRatio()) {
                reasons.add(String.format("Moderate liquidation activity (%.2f%% of OI)",
                        liquidationRatio * 100));
            } else {
                // "+10 for low liquidation" removed: crypto markets sit in this zone by default,
                // which made every signal every cycle carry a +10 bias. Informational only.
                reasons.add("Low liquidation activity — stable positioning (informational)");
            }
        }

        if (oiUsd != null) {
            reasons.add(String.format("Open interest $%s", formatOi(oiUsd)));
        }

        if (reasons.isEmpty()) {
            reasons.add("Insufficient order book data");
        }

        return new DimensionScore("Order Book", clamp(score, -100, 100), weight, reasons);
    }

    private String formatOi(double value) {
        if (value >= 1e9) return String.format("%.1fB", value / 1e9);
        if (value >= 1e6) return String.format("%.1fM", value / 1e6);
        return String.format("%.0f", value);
    }

    private DimensionScore scoreMacro(Map<String, Object> macroData, String symbol,
                                      SignalConfig.MacroBtcDominance btcDom, double weight) {
        if (macroData == null) {
            return new DimensionScore("Macro", 0, weight, List.of("No macro data available"));
        }

        double score = 0;
        List<String> reasons = new ArrayList<>();
        boolean isBtc = symbol.toUpperCase().startsWith("BTC");

        Double btcDominance = asDouble(macroData.get("btcDominance"));
        if (btcDominance != null) {
            if (btcDominance > btcDom.threshold()) {
                if (isBtc) {
                    score += btcDom.scoreBtcWhenHigh();
                    reasons.add(String.format("BTC dominance %.1f%% — bullish for BTC", btcDominance));
                } else {
                    score += btcDom.scoreAltWhenHigh();
                    reasons.add(String.format("BTC dominance %.1f%% — bearish for alts", btcDominance));
                }
            } else {
                // Previously omitted the BTC-below-50 branch entirely — that asymmetry
                // gave BTC a neutral (0) score while alts got +20. BTC should carry a
                // mild bearish bias when rotation is toward alts.
                if (isBtc) {
                    score += btcDom.scoreBtcWhenLow();
                    reasons.add(String.format("BTC dominance %.1f%% — mildly bearish for BTC (rotation to alts)",
                            btcDominance));
                } else {
                    score += btcDom.scoreAltWhenLow();
                    reasons.add(String.format("BTC dominance %.1f%% — bullish for alts", btcDominance));
                }
            }
        }

        Double totalStablecoinCap = asDouble(macroData.get("totalStablecoinCap"));
        if (totalStablecoinCap != null) {
            // Absolute thresholds removed: stablecoin cap has sat above $150B continuously,
            // producing a constant +20 bias to every signal. Informational only.
            if (totalStablecoinCap > 150_000_000_000.0) {
                reasons.add("Stablecoin supply above $150B (informational)");
            } else if (totalStablecoinCap > 100_000_000_000.0) {
                reasons.add("Stablecoin supply moderate (informational)");
            } else {
                reasons.add("Stablecoin supply below $100B (informational)");
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("Insufficient macro data for scoring");
        }

        return new DimensionScore("Macro", clamp(score, -100, 100), weight, reasons);
    }

    // --- Composite helpers ---

    private double computeOverallScore(List<DimensionScore> dimensions, SignalConfig.Weights weights) {
        double score = 0;
        for (DimensionScore dim : dimensions) {
            score += dim.score() * dim.weight();
        }
        return clamp(score, -100, 100);
    }

    /**
     * Alignment score — measures how strongly weighted dimensions agree on a
     * direction. Despite the legacy name "confidence" (renamed in PR6c), this
     * metric does NOT predict win rate: outcome analysis showed an inverse
     * correlation between alignment and actual outcomes. The honest reading
     * is "how much does the scoring stack agree internally", not "how likely
     * is this signal to be profitable".
     */
    private int computeAlignment(List<DimensionScore> dimensions, double overallScore,
                                 SignalConfig.Alignment al) {
        if (Math.abs(overallScore) < al.minScoreForNonZero()) return al.minOutput();

        boolean isPositive       = overallScore > 0;
        double weightedStrength  = 0;
        double totalWeight       = 0;
        int contradictions       = 0;

        for (DimensionScore dim : dimensions) {
            double w = dim.weight();
            totalWeight += w;
            double s = dim.score();
            boolean aligned = isPositive ? s > 0 : s < 0;
            double absScore = Math.abs(s);

            if (aligned) {
                weightedStrength += (absScore / 100.0) * w;
            } else if (absScore >= al.contradictionScoreThreshold()) {
                contradictions++;
                weightedStrength -= (absScore / 100.0) * w * al.contradictionPenaltyMultiplier();
            }
        }

        double raw = totalWeight > 0 ? (weightedStrength / totalWeight) * 100 : 0;

        if (contradictions >= 2) raw *= al.twoContradictionPenalty();
        else if (contradictions >= 1) raw *= al.oneContradictionPenalty();

        int alignment = (int) (raw * al.outputScale());
        return Math.max(al.minOutput(), Math.min(al.maxOutput(), alignment));
    }

    private String determineSignalLabel(double score, int alignment,
                                        com.cryptoradar.signal.model.MarketRegime regime,
                                        SignalConfig.SignalLabels labels) {
        int[] thresholds = regimeAdjustedThresholds(regime, labels);
        int strongBuyMin  = thresholds[0];
        int buyMin        = thresholds[1];
        int strongSellMax = thresholds[2];
        int sellMax       = thresholds[3];

        SignalConfig.RegimeThresholds chop = labels.chop();
        if (score >= strongBuyMin  && alignment >= chop.strongAlignmentMin()) return STRONG_BUY;
        if (score >= buyMin        && alignment >= chop.alignmentMin())       return BUY;
        if (score <= strongSellMax && alignment >= chop.strongAlignmentMin()) return STRONG_SELL;
        if (score <= sellMax       && alignment >= chop.alignmentMin())       return SELL;
        return NEUTRAL;
    }

    /**
     * Regime-modulated thresholds. In a strong regime, counter-trend signals
     * need more evidence to fire; trend-aligned signals keep their defaults.
     *
     * <p>Returns {@code [strongBuyMin, buyMin, strongSellMax, sellMax]}.
     * The SELL-side defaults are intentionally looser than BUY-side
     * (transitional asymmetry per PR3); regime modulation layers on top of that.
     */
    // Package-private for unit tests — pure function returning the threshold
    // tuple so tests can assert BULL/BEAR modulation without constructing
    // a full scoring pipeline.
    int[] regimeAdjustedThresholds(com.cryptoradar.signal.model.MarketRegime regime) {
        return regimeAdjustedThresholds(regime, resolveConfig().signalLabels());
    }

    private int[] regimeAdjustedThresholds(com.cryptoradar.signal.model.MarketRegime regime,
                                           SignalConfig.SignalLabels labels) {
        SignalConfig.RegimeThresholds chop = labels.chop();
        int strongBuy  = (int) chop.strongBuyMinScore();
        int buy        = (int) chop.buyMinScore();
        int strongSell = (int) chop.strongSellMaxScore();
        int sell       = (int) chop.sellMaxScore();

        switch (regime) {
            case BULL -> {
                // Counter-trend SELLs need stronger evidence — revert to symmetric thresholds.
                strongSell = (int) labels.bull().strongSellMaxScore();
                sell       = (int) labels.bull().sellMaxScore();
            }
            case BEAR -> {
                // Counter-trend BUYs face a higher bar — don't catch falling knives.
                strongBuy = (int) labels.bear().strongBuyMinScore();
                buy       = (int) labels.bear().buyMinScore();
            }
            case CHOP, UNKNOWN -> {
                // defaults already set
            }
        }
        return new int[]{strongBuy, buy, strongSell, sell};
    }

    private String determineAlertLevel(String signalLabel, int alignment,
                                       SignalConfig.RegimeThresholds thresholds) {
        if ((STRONG_BUY.equals(signalLabel) || STRONG_SELL.equals(signalLabel))
                && alignment >= thresholds.strongAlignmentMin()) {
            return "OPPORTUNITY";
        }
        if ((BUY.equals(signalLabel) || SELL.equals(signalLabel))
                && alignment >= thresholds.alignmentMin()) {
            return "WATCH";
        }
        return "NEUTRAL";
    }

    private void populateTradeLevels(TradingSignal signal, Map<String, Object> analytics,
                                     Map<String, Object> priceData, SignalConfig.TradeLevels tl) {
        if (priceData == null || analytics == null) return;

        Double price      = asDouble(priceData.get("price"));
        Double support    = asDouble(analytics.get("supportLevel"));
        Double resistance = asDouble(analytics.get("resistanceLevel"));

        Map<String, Object> indicators = asMap(analytics.get("technicalIndicators"));
        Double atr = asDouble(indicators != null ? indicators.get("atr14") : null);

        if (price == null || price <= 0) return;

        double atrValue = (atr != null && atr > 0) ? atr : price * 0.02;
        String signalLabel = signal.getSignal();

        if (BUY.equals(signalLabel) || STRONG_BUY.equals(signalLabel)) {
            populateLongLevels(signal, price, support, resistance, atrValue, tl);
        } else if (SELL.equals(signalLabel) || STRONG_SELL.equals(signalLabel)) {
            populateShortLevels(signal, price, support, resistance, atrValue, tl);
        }

        computeRiskReward(signal);
    }

    private void populateLongLevels(TradingSignal signal, double price,
                                    Double support, Double resistance,
                                    double atrValue, SignalConfig.TradeLevels tl) {
        signal.setSuggestedEntry(round2(price));

        double stopFromAtr     = price - (atrValue * tl.atrStopMultiple());
        double stopFromSupport = (support != null && support > 0)
                ? support - (atrValue * tl.supportStopAtrBuffer())
                : stopFromAtr;
        double stopLoss = enforceMinRiskDistance(price, Math.min(stopFromAtr, stopFromSupport), true, tl);
        signal.setSuggestedStopLoss(round2(stopLoss));

        double risk              = price - stopLoss;
        double minTarget         = price + (risk * tl.minRr());
        double resistanceTarget  = (resistance != null && resistance > price) ? resistance : minTarget;
        signal.setSuggestedTakeProfit(round2(Math.max(minTarget, resistanceTarget)));
    }

    private void populateShortLevels(TradingSignal signal, double price,
                                     Double support, Double resistance,
                                     double atrValue, SignalConfig.TradeLevels tl) {
        signal.setSuggestedEntry(round2(price));

        double stopFromAtr        = price + (atrValue * tl.atrStopMultiple());
        double stopFromResistance = (resistance != null && resistance > 0)
                ? resistance + (atrValue * tl.supportStopAtrBuffer())
                : stopFromAtr;
        double stopLoss = enforceMinRiskDistance(price, Math.max(stopFromAtr, stopFromResistance), false, tl);
        signal.setSuggestedStopLoss(round2(stopLoss));

        double risk          = stopLoss - price;
        double minTarget     = price - (risk * tl.minRr());
        double supportTarget = (support != null && support > 0 && support < price) ? support : minTarget;
        signal.setSuggestedTakeProfit(round2(Math.min(minTarget, supportTarget)));
    }

    /**
     * Widens the stop to {@code minRiskPct} of entry price if the computed
     * stop sat inside that floor. Returns the stop unchanged otherwise.
     */
    private double enforceMinRiskDistance(double entry, double stop, boolean isLong,
                                          SignalConfig.TradeLevels tl) {
        double actualRisk = Math.abs(entry - stop);
        double minRisk    = entry * tl.minRiskPct();
        if (actualRisk >= minRisk) return stop;
        return isLong ? entry - minRisk : entry + minRisk;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void computeRiskReward(TradingSignal signal) {
        if (signal.getSuggestedEntry() == null || signal.getSuggestedStopLoss() == null
                || signal.getSuggestedTakeProfit() == null) {
            return;
        }

        double entry  = signal.getSuggestedEntry();
        double stop   = signal.getSuggestedStopLoss();
        double target = signal.getSuggestedTakeProfit();

        double risk   = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);

        if (risk > 0) {
            signal.setRiskRewardRatio(Math.round((reward / risk) * 10.0) / 10.0);
        }
    }

    // --- Internal helpers ---

    private SignalConfig resolveConfig() {
        SignalConfig config = configService.getActive();
        if (config == null) {
            LOG.warn("ConfigService returned null — falling back to SignalConfig.defaults()");
            return SignalConfig.defaults();
        }
        return config;
    }

    private void warnIfWeightSumOff(SignalConfig.Weights weights) {
        double sum = weights.sum();
        if (Math.abs(sum - 1.0) > WEIGHT_SUM_WARN_TOLERANCE) {
            MDC.put("weightSum", String.format("%.4f", sum));
            try {
                LOG.warnf("Signal weights sum to %.4f — expected 1.0 ±%.2f. Scores will be skewed.",
                        sum, WEIGHT_SUM_WARN_TOLERANCE);
            } finally {
                MDC.remove("weightSum");
            }
        }
    }

    // --- Utility ---

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map) return (Map<String, Object>) obj;
        return null;
    }

    private static Double asDouble(Object obj) {
        if (obj instanceof Number number) return number.doubleValue();
        if (obj instanceof String str) {
            try { return Double.parseDouble(str); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Integer asInt(Object obj) {
        if (obj instanceof Number number) return number.intValue();
        if (obj instanceof String str) {
            try { return Integer.parseInt(str); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
