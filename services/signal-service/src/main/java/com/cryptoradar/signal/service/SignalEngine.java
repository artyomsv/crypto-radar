package com.cryptoradar.signal.service;

import com.cryptoradar.signal.model.DimensionScore;
import com.cryptoradar.signal.model.TradingSignal;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core multi-dimensional scoring algorithm.
 * Each dimension produces a score from -100 to +100, combined with weights
 * to produce an overall trading signal with confidence.
 */
@ApplicationScoped
public class SignalEngine {

    private static final Logger LOG = Logger.getLogger(SignalEngine.class);

    // Coin-specific signals (65%): differentiate individual assets
    private static final double WEIGHT_TECHNICAL = 0.35;
    private static final double WEIGHT_WHALE = 0.20;
    private static final double WEIGHT_ORDER_BOOK = 0.10;
    // Market-wide signals (35%): shared context, can't distinguish coins
    private static final double WEIGHT_DERIVATIVES = 0.15;
    private static final double WEIGHT_SENTIMENT = 0.10;
    private static final double WEIGHT_MACRO = 0.10;

    static {
        double sum = WEIGHT_TECHNICAL + WEIGHT_WHALE + WEIGHT_ORDER_BOOK
                   + WEIGHT_DERIVATIVES + WEIGHT_SENTIMENT + WEIGHT_MACRO;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalStateException("Signal weights must sum to 1.0, got " + sum);
        }
    }

    private static final String STRONG_BUY = "STRONG_BUY";
    private static final String BUY = "BUY";
    private static final String NEUTRAL = "NEUTRAL";
    private static final String SELL = "SELL";
    private static final String STRONG_SELL = "STRONG_SELL";

    public TradingSignal computeSignal(String symbol,
                                       Map<String, Object> analytics,
                                       Map<String, Object> whaleData,
                                       Map<String, Object> derivativesData,
                                       Map<String, Object> priceData,
                                       Map<String, Object> macroData) {
        DimensionScore technical = scoreTechnical(analytics);
        DimensionScore whale = scoreWhale(whaleData);
        DimensionScore derivatives = scoreDerivatives(derivativesData);
        DimensionScore sentiment = scoreSentiment(analytics, macroData);
        DimensionScore orderBook = scoreOrderBook(derivativesData);
        DimensionScore macro = scoreMacro(macroData, symbol);

        List<DimensionScore> dimensions = List.of(
                technical, whale, derivatives, sentiment, orderBook, macro);

        double overallScore = technical.score() * WEIGHT_TECHNICAL
                + whale.score() * WEIGHT_WHALE
                + derivatives.score() * WEIGHT_DERIVATIVES
                + sentiment.score() * WEIGHT_SENTIMENT
                + orderBook.score() * WEIGHT_ORDER_BOOK
                + macro.score() * WEIGHT_MACRO;

        overallScore = clamp(overallScore, -100, 100);

        int confidence = computeConfidence(dimensions, overallScore);
        String signalLabel = determineSignalLabel(overallScore, confidence);

        TradingSignal signal = new TradingSignal();
        signal.setSymbol(symbol);
        signal.setTimestamp(Instant.now());
        signal.setSignal(signalLabel);
        signal.setOverallScore(Math.round(overallScore * 100.0) / 100.0);
        signal.setConfidence(confidence);
        signal.setDimensions(new ArrayList<>(dimensions));
        signal.setAlertLevel(determineAlertLevel(signalLabel, confidence));

        populateTradeLevels(signal, analytics, priceData);

        return signal;
    }

    // --- Dimension scoring ---

    private DimensionScore scoreTechnical(Map<String, Object> analytics) {
        if (analytics == null) {
            return new DimensionScore("Technical", 0, WEIGHT_TECHNICAL, List.of("No analytics data available"));
        }

        List<String> reasons = new ArrayList<>();
        Map<String, Object> indicators = asMap(analytics.get("technicalIndicators"));

        double score = computeRsiScore(indicators, reasons)
                + computeMacdScore(indicators, reasons)
                + computeSma200Score(analytics, indicators, reasons)
                + computeSupportResistanceScore(analytics, reasons)
                + computeBollingerScore(indicators, reasons)
                + computeVolumeConfirmation(analytics, reasons);

        return new DimensionScore("Technical", clamp(score, -100, 100), WEIGHT_TECHNICAL, reasons);
    }

    private double computeRsiScore(Map<String, Object> indicators, List<String> reasons) {
        Double rsi = asDouble(indicators != null ? indicators.get("rsi14") : null);
        if (rsi == null) return 0;

        if (rsi < 30) { reasons.add(String.format("RSI at %.1f — oversold (bullish)", rsi)); return 80; }
        if (rsi < 40) { reasons.add(String.format("RSI at %.1f — approaching oversold", rsi)); return 40; }
        if (rsi > 70) { reasons.add(String.format("RSI at %.1f — overbought (bearish)", rsi)); return -80; }
        if (rsi > 60) { reasons.add(String.format("RSI at %.1f — approaching overbought", rsi)); return -40; }
        reasons.add(String.format("RSI at %.1f — neutral", rsi));
        return 0;
    }

    private double computeMacdScore(Map<String, Object> indicators, List<String> reasons) {
        Double macdHistogram = asDouble(indicators != null ? indicators.get("macdHistogram") : null);
        if (macdHistogram == null) return 0;

        if (macdHistogram > 0) { reasons.add("MACD histogram positive (bullish momentum)"); return 30; }
        reasons.add("MACD histogram negative (bearish momentum)");
        return -30;
    }

    private double computeSma200Score(Map<String, Object> analytics, Map<String, Object> indicators, List<String> reasons) {
        Double sma200 = asDouble(indicators != null ? indicators.get("sma200") : null);
        if (sma200 == null || sma200 <= 0) return 0;

        Double support = asDouble(analytics.get("supportLevel"));
        Double resistance = asDouble(analytics.get("resistanceLevel"));
        if (support == null || resistance == null || support <= 0 || resistance <= 0) return 0;

        double midPrice = (support + resistance) / 2;
        if (midPrice > sma200) { reasons.add("Price above SMA200 (bullish macro trend)"); return 35; }
        reasons.add("Price below SMA200 (bearish macro trend — major headwind)");
        return -35;
    }

    private double computeSupportResistanceScore(Map<String, Object> analytics, List<String> reasons) {
        Double support = asDouble(analytics.get("supportLevel"));
        Double resistance = asDouble(analytics.get("resistanceLevel"));
        if (support == null || resistance == null || support <= 0 || resistance <= 0) return 0;
        if (resistance - support <= 0) return 0;

        Double overallScore = asDouble(analytics.get("overallScore"));
        if (overallScore == null) return 0;

        double position = overallScore / 100.0;
        if (position < 0.3) {
            reasons.add(String.format("Near support %.2f (potential bounce)", support));
            return 30;
        } else if (position > 0.7) {
            reasons.add(String.format("Near resistance %.2f (potential rejection)", resistance));
            return -30;
        }
        return 0;
    }

    private double computeVolumeConfirmation(Map<String, Object> analytics, List<String> reasons) {
        Object volumeTrend = analytics.get("volumeTrend");
        String trend = volumeTrend != null ? volumeTrend.toString() : null;
        if ("DECREASING".equals(trend)) {
            reasons.add("Volume declining — weakens directional conviction");
            return -10;
        }
        if ("INCREASING".equals(trend)) {
            reasons.add("Volume increasing — trend has conviction");
            return 5;
        }
        return 0;
    }

    private double computeBollingerScore(Map<String, Object> indicators, List<String> reasons) {
        if (indicators == null) return 0;

        Double bbUpper = asDouble(indicators.get("bollingerUpper"));
        Double bbLower = asDouble(indicators.get("bollingerLower"));
        Double bbMiddle = asDouble(indicators.get("bollingerMiddle"));

        if (bbUpper == null || bbLower == null || bbMiddle == null) return 0;
        if (bbUpper <= bbLower) return 0;

        double range = bbUpper - bbLower;
        double position = (bbMiddle - bbLower) / range;

        if (position < 0.3) {
            reasons.add("Price near lower Bollinger band (potential reversal up)");
            return 20;
        } else if (position > 0.7) {
            reasons.add("Price near upper Bollinger band (potential reversal down)");
            return -20;
        }
        return 0;
    }

    private DimensionScore scoreWhale(Map<String, Object> whaleData) {
        if (whaleData == null) {
            return new DimensionScore("Whale", 0, WEIGHT_WHALE, List.of("No whale data available"));
        }

        List<String> reasons = new ArrayList<>();
        Double pressure = asDouble(whaleData.get("whalePressure"));

        if (pressure == null) {
            return new DimensionScore("Whale", 0, WEIGHT_WHALE, List.of("Whale pressure data unavailable"));
        }

        double score = clamp(pressure, -100, 100);
        reasons.add(String.format("Whale pressure %+.0f — %s", pressure,
                pressure > 30 ? "heavy buying" : pressure < -30 ? "heavy selling" : "balanced"));

        // Sample size gate: few trades = statistically meaningless pressure reading
        Integer tradeCount = asInt(whaleData.get("tradeCount1h"));
        if (tradeCount != null) {
            if (tradeCount < 15) {
                double dampening = tradeCount / 15.0;
                score *= dampening;
                reasons.add(String.format("%d whale trades in 1h — low sample, signal at %.0f%%", tradeCount, dampening * 100));
            } else if (tradeCount >= 30) {
                score *= 1.20;
                reasons.add(String.format("%d whale trades in 1h — high activity, signal amplified", tradeCount));
            } else {
                reasons.add(String.format("%d whale trades in 1h — moderate activity", tradeCount));
            }
        }

        return new DimensionScore("Whale", clamp(score, -100, 100), WEIGHT_WHALE, reasons);
    }

    private DimensionScore scoreDerivatives(Map<String, Object> derivativesData) {
        if (derivativesData == null) {
            return new DimensionScore("Derivatives", 0, WEIGHT_DERIVATIVES, List.of("No derivatives data available"));
        }

        double score = 0;
        List<String> reasons = new ArrayList<>();

        // Funding rate — contrarian, graduated and symmetric
        Double fundingRate = asDouble(derivativesData.get("fundingRate"));
        if (fundingRate != null) {
            double fundingPct = fundingRate * 100;
            double absFunding = Math.abs(fundingRate);

            if (absFunding < 0.0003) {
                reasons.add(String.format("Funding rate %.4f%% — neutral range", fundingPct));
            } else {
                double fundingScore;
                if (absFunding >= 0.0015) {
                    fundingScore = 50;
                } else if (absFunding >= 0.0008) {
                    fundingScore = 35;
                } else {
                    fundingScore = 20;
                }

                if (fundingRate < 0) {
                    score += fundingScore;
                    reasons.add(String.format("Funding rate %.4f%% — shorts overcrowded, %s contrarian bullish",
                            fundingPct, fundingScore >= 35 ? "strongly" : "mildly"));
                } else {
                    score -= fundingScore;
                    reasons.add(String.format("Funding rate %.4f%% — longs overcrowded, %s contrarian bearish",
                            fundingPct, fundingScore >= 35 ? "strongly" : "mildly"));
                }
            }
        }

        // Long/short ratio — contrarian
        Double longPct = asDouble(derivativesData.get("longPct"));
        if (longPct != null) {
            if (longPct > 70) {
                score -= 35;
                reasons.add(String.format("%.0f%% long — extremely crowded (bearish)", longPct));
            } else if (longPct > 60) {
                score -= 20;
                reasons.add(String.format("%.0f%% long — crowded longs (bearish)", longPct));
            } else if (longPct < 30) {
                score += 35;
                reasons.add(String.format("%.0f%% long — extremely crowded shorts (bullish)", longPct));
            } else if (longPct < 40) {
                score += 20;
                reasons.add(String.format("%.0f%% long — crowded shorts (bullish)", longPct));
            } else {
                reasons.add(String.format("%.0f%% long — balanced positioning", longPct));
            }
        }

        return new DimensionScore("Derivatives", clamp(score, -100, 100), WEIGHT_DERIVATIVES, reasons);
    }

    private DimensionScore scoreSentiment(Map<String, Object> analytics, Map<String, Object> macroData) {
        double score = 0;
        List<String> reasons = new ArrayList<>();

        // News sentiment from analytics
        if (analytics != null) {
            Double sentimentScore = asDouble(analytics.get("sentimentScore"));
            if (sentimentScore != null) {
                score += sentimentScore * 50;
                reasons.add(String.format("News sentiment %.2f — %s", sentimentScore,
                        sentimentScore > 0.3 ? "positive" : sentimentScore < -0.3 ? "negative" : "neutral"));
            }
        }

        // Fear & Greed — contrarian, graduated scale
        if (macroData != null) {
            Integer fearGreed = asInt(macroData.get("fearGreedIndex"));
            if (fearGreed != null) {
                if (fearGreed <= 10) {
                    score += 30;
                    reasons.add(String.format("Fear & Greed at %d — extreme fear (strong contrarian buy)", fearGreed));
                } else if (fearGreed <= 25) {
                    score += 15;
                    reasons.add(String.format("Fear & Greed at %d — fear (mild contrarian buy)", fearGreed));
                } else if (fearGreed >= 90) {
                    score -= 30;
                    reasons.add(String.format("Fear & Greed at %d — extreme greed (strong contrarian sell)", fearGreed));
                } else if (fearGreed >= 75) {
                    score -= 15;
                    reasons.add(String.format("Fear & Greed at %d — greed (mild contrarian sell)", fearGreed));
                } else {
                    reasons.add(String.format("Fear & Greed at %d — neutral zone", fearGreed));
                }
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("No sentiment data available");
        }

        return new DimensionScore("Sentiment", clamp(score, -100, 100), WEIGHT_SENTIMENT, reasons);
    }

    private DimensionScore scoreOrderBook(Map<String, Object> derivativesData) {
        // Scores based on OI and liquidation data — L/S ratio is already used in scoreDerivatives
        if (derivativesData == null) {
            return new DimensionScore("Order Book", 0, WEIGHT_ORDER_BOOK, List.of("No order book data available"));
        }

        double score = 0;
        List<String> reasons = new ArrayList<>();

        Double liquidations24h = asDouble(derivativesData.get("liquidations24hUsd"));
        Double oiUsd = asDouble(derivativesData.get("openInterestUsd"));

        if (liquidations24h != null && oiUsd != null && oiUsd > 0) {
            double liquidationRatio = liquidations24h / oiUsd;
            if (liquidationRatio > 0.05) {
                score -= 20;
                reasons.add(String.format("High liquidation activity (%.1f%% of OI) — volatile, risky", liquidationRatio * 100));
            } else if (liquidationRatio > 0.01) {
                reasons.add(String.format("Moderate liquidation activity (%.2f%% of OI)", liquidationRatio * 100));
            } else {
                score += 10;
                reasons.add("Low liquidation activity — stable positioning");
            }
        }

        if (oiUsd != null) {
            reasons.add(String.format("Open interest $%s", formatOi(oiUsd)));
        }

        if (reasons.isEmpty()) {
            reasons.add("Insufficient order book data");
        }

        return new DimensionScore("Order Book", clamp(score, -100, 100), WEIGHT_ORDER_BOOK, reasons);
    }

    private String formatOi(double value) {
        if (value >= 1e9) return String.format("%.1fB", value / 1e9);
        if (value >= 1e6) return String.format("%.1fM", value / 1e6);
        return String.format("%.0f", value);
    }

    private DimensionScore scoreMacro(Map<String, Object> macroData, String symbol) {
        if (macroData == null) {
            return new DimensionScore("Macro", 0, WEIGHT_MACRO, List.of("No macro data available"));
        }

        double score = 0;
        List<String> reasons = new ArrayList<>();
        boolean isBtc = symbol.toUpperCase().startsWith("BTC");

        Double btcDominance = asDouble(macroData.get("btcDominance"));
        if (btcDominance != null) {
            if (btcDominance > 50) {
                if (isBtc) {
                    score += 20;
                    reasons.add(String.format("BTC dominance %.1f%% (rising) — bullish for BTC", btcDominance));
                } else {
                    score -= 20;
                    reasons.add(String.format("BTC dominance %.1f%% (rising) — bearish for alts", btcDominance));
                }
            } else {
                if (!isBtc) {
                    score += 20;
                    reasons.add(String.format("BTC dominance %.1f%% (falling) — bullish for alts", btcDominance));
                }
            }
        }

        Double totalStablecoinCap = asDouble(macroData.get("totalStablecoinCap"));
        if (totalStablecoinCap != null) {
            if (totalStablecoinCap > 150_000_000_000.0) {
                score += 20;
                reasons.add("Stablecoin supply above $150B — significant dry powder");
            } else if (totalStablecoinCap > 100_000_000_000.0) {
                reasons.add("Stablecoin supply moderate");
            } else {
                score -= 20;
                reasons.add("Stablecoin supply below $100B — limited dry powder");
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("Insufficient macro data for scoring");
        }

        return new DimensionScore("Macro", clamp(score, -100, 100), WEIGHT_MACRO, reasons);
    }

    // --- Composite helpers ---

    /**
     * Confidence based on weighted signal strength, not binary alignment.
     * A dimension at +80 contributes far more than one at +1.
     * Strong contradictions actively reduce confidence.
     * Max confidence capped at 90% — no signal is ever certain.
     */
    private int computeConfidence(List<DimensionScore> dimensions, double overallScore) {
        if (Math.abs(overallScore) < 5) return 15;

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
            } else if (absScore >= 30) {
                contradictions++;
                weightedStrength -= (absScore / 100.0) * w * 0.5;
            }
        }

        double raw = totalWeight > 0 ? (weightedStrength / totalWeight) * 100 : 0;

        if (contradictions >= 2) raw *= 0.6;
        else if (contradictions >= 1) raw *= 0.8;

        int confidence = (int) (raw * 0.9);
        return Math.max(15, Math.min(90, confidence));
    }

    private String determineSignalLabel(double score, int confidence) {
        // Thresholds calibrated to new strength-based confidence (max 90%)
        if (score >= 55 && confidence >= 60) return STRONG_BUY;
        if (score >= 25 && confidence >= 35) return BUY;
        if (score <= -55 && confidence >= 60) return STRONG_SELL;
        if (score <= -25 && confidence >= 35) return SELL;
        return NEUTRAL;
    }

    private String determineAlertLevel(String signalLabel, int confidence) {
        if ((STRONG_BUY.equals(signalLabel) || STRONG_SELL.equals(signalLabel)) && confidence >= 60) {
            return "OPPORTUNITY";
        }
        if ((BUY.equals(signalLabel) || SELL.equals(signalLabel)) && confidence >= 35) {
            return "WATCH";
        }
        return "NEUTRAL";
    }

    private void populateTradeLevels(TradingSignal signal, Map<String, Object> analytics, Map<String, Object> priceData) {
        if (priceData == null || analytics == null) return;

        Double price = asDouble(priceData.get("price"));
        Double support = asDouble(analytics.get("supportLevel"));
        Double resistance = asDouble(analytics.get("resistanceLevel"));

        Map<String, Object> indicators = asMap(analytics.get("technicalIndicators"));
        Double atr = asDouble(indicators != null ? indicators.get("atr14") : null);

        if (price == null || price <= 0) return;

        double atrValue = (atr != null && atr > 0) ? atr : price * 0.02;
        double MIN_RR = 5.0;

        String signalLabel = signal.getSignal();
        if (BUY.equals(signalLabel) || STRONG_BUY.equals(signalLabel)) {
            signal.setSuggestedEntry(round2(price));

            double stopFromAtr = price - (atrValue * 1.5);
            double stopFromSupport = (support != null && support > 0) ? support - (atrValue * 0.5) : stopFromAtr;
            double stopLoss = Math.min(stopFromAtr, stopFromSupport);
            signal.setSuggestedStopLoss(round2(stopLoss));

            double risk = price - stopLoss;
            double minTarget = price + (risk * MIN_RR);
            double resistanceTarget = (resistance != null && resistance > price) ? resistance : minTarget;
            double takeProfit = Math.max(minTarget, resistanceTarget);
            signal.setSuggestedTakeProfit(round2(takeProfit));

        } else if (SELL.equals(signalLabel) || STRONG_SELL.equals(signalLabel)) {
            signal.setSuggestedEntry(round2(price));

            double stopFromAtr = price + (atrValue * 1.5);
            double stopFromResistance = (resistance != null && resistance > 0) ? resistance + (atrValue * 0.5) : stopFromAtr;
            double stopLoss = Math.max(stopFromAtr, stopFromResistance);
            signal.setSuggestedStopLoss(round2(stopLoss));

            double risk = stopLoss - price;
            double minTarget = price - (risk * MIN_RR);
            double supportTarget = (support != null && support > 0 && support < price) ? support : minTarget;
            double takeProfit = Math.min(minTarget, supportTarget);
            signal.setSuggestedTakeProfit(round2(takeProfit));
        }

        computeRiskReward(signal);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void computeRiskReward(TradingSignal signal) {
        if (signal.getSuggestedEntry() == null || signal.getSuggestedStopLoss() == null
                || signal.getSuggestedTakeProfit() == null) {
            return;
        }

        double entry = signal.getSuggestedEntry();
        double stop = signal.getSuggestedStopLoss();
        double target = signal.getSuggestedTakeProfit();

        double risk = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);

        if (risk > 0) {
            signal.setRiskRewardRatio(Math.round((reward / risk) * 10.0) / 10.0);
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
