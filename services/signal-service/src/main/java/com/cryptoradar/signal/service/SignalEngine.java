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

    private static final double WEIGHT_TECHNICAL = 0.25;
    private static final double WEIGHT_WHALE = 0.20;
    private static final double WEIGHT_DERIVATIVES = 0.20;
    private static final double WEIGHT_SENTIMENT = 0.15;
    private static final double WEIGHT_ORDER_BOOK = 0.10;
    private static final double WEIGHT_MACRO = 0.10;

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

        double score = 0;
        List<String> reasons = new ArrayList<>();

        Map<String, Object> indicators = asMap(analytics.get("technicalIndicators"));

        Double rsi = asDouble(indicators != null ? indicators.get("rsi14") : null);
        if (rsi != null) {
            if (rsi < 30) { score += 80; reasons.add(String.format("RSI at %.1f — oversold (bullish)", rsi)); }
            else if (rsi < 40) { score += 40; reasons.add(String.format("RSI at %.1f — approaching oversold", rsi)); }
            else if (rsi > 70) { score -= 80; reasons.add(String.format("RSI at %.1f — overbought (bearish)", rsi)); }
            else if (rsi > 60) { score -= 40; reasons.add(String.format("RSI at %.1f — approaching overbought", rsi)); }
            else { reasons.add(String.format("RSI at %.1f — neutral", rsi)); }
        }

        Double macdHistogram = asDouble(indicators != null ? indicators.get("macdHistogram") : null);
        if (macdHistogram != null) {
            if (macdHistogram > 0) { score += 30; reasons.add("MACD histogram positive (bullish momentum)"); }
            else { score -= 30; reasons.add("MACD histogram negative (bearish momentum)"); }
        }

        Double sma200 = asDouble(indicators != null ? indicators.get("sma200") : null);
        Double currentPrice = asDouble(analytics.get("supportLevel") != null ? null : null);
        // Use support/resistance proximity as price proxy
        if (sma200 != null && sma200 > 0) {
            Double support = asDouble(analytics.get("supportLevel"));
            Double resistance = asDouble(analytics.get("resistanceLevel"));
            if (support != null && resistance != null && support > 0 && resistance > 0) {
                double midPrice = (support + resistance) / 2;
                if (midPrice > sma200) { score += 20; reasons.add("Price above SMA200 (bullish trend)"); }
                else { score -= 20; reasons.add("Price below SMA200 (bearish trend)"); }
            }
        }

        addSupportResistanceScore(analytics, reasons, score);
        double srScore = computeSupportResistanceScore(analytics, reasons);
        score += srScore;

        score += computeBollingerScore(indicators, reasons);

        return new DimensionScore("Technical", clamp(score, -100, 100), WEIGHT_TECHNICAL, reasons);
    }

    private double computeSupportResistanceScore(Map<String, Object> analytics, List<String> reasons) {
        Double support = asDouble(analytics.get("supportLevel"));
        Double resistance = asDouble(analytics.get("resistanceLevel"));
        if (support == null || resistance == null || support <= 0 || resistance <= 0) return 0;

        double range = resistance - support;
        if (range <= 0) return 0;

        // Estimate where price is within the range using overall score as proxy
        Double overallScore = asDouble(analytics.get("overallScore"));
        if (overallScore == null) return 0;

        // overallScore 0-100 maps to position in the support/resistance range
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

    private void addSupportResistanceScore(Map<String, Object> analytics, List<String> reasons, double currentScore) {
        // Intentionally empty — scoring handled in computeSupportResistanceScore
    }

    private double computeBollingerScore(Map<String, Object> indicators, List<String> reasons) {
        if (indicators == null) return 0;

        Double bbUpper = asDouble(indicators.get("bollingerUpper"));
        Double bbLower = asDouble(indicators.get("bollingerLower"));
        Double bbMiddle = asDouble(indicators.get("bollingerMiddle"));

        if (bbUpper == null || bbLower == null || bbMiddle == null) return 0;
        if (bbUpper <= bbLower) return 0;

        double range = bbUpper - bbLower;
        // Use middle as reference — closer to lower = bullish, closer to upper = bearish
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

        Integer tradeCount = asInt(whaleData.get("tradeCount1h"));
        if (tradeCount != null && tradeCount > 5) {
            double amplification = score * 0.20;
            score += amplification;
            reasons.add(String.format("%d whale trades in 1h — amplified signal by 20%%", tradeCount));
        }

        return new DimensionScore("Whale", clamp(score, -100, 100), WEIGHT_WHALE, reasons);
    }

    private DimensionScore scoreDerivatives(Map<String, Object> derivativesData) {
        if (derivativesData == null) {
            return new DimensionScore("Derivatives", 0, WEIGHT_DERIVATIVES, List.of("No derivatives data available"));
        }

        double score = 0;
        List<String> reasons = new ArrayList<>();

        // Funding rate — contrarian
        Double fundingRate = asDouble(derivativesData.get("fundingRate"));
        if (fundingRate != null) {
            double fundingPct = fundingRate * 100; // convert to percentage
            if (fundingRate > 0.0005) {
                score -= 50;
                reasons.add(String.format("Funding rate %.4f%% — longs overcrowded (bearish)", fundingPct));
            } else if (fundingRate < -0.0001) {
                score += 50;
                reasons.add(String.format("Funding rate %.4f%% — shorts overcrowded (bullish)", fundingPct));
            } else {
                reasons.add(String.format("Funding rate %.4f%% — neutral", fundingPct));
            }
        }

        // Long/short ratio — contrarian
        Double longPct = asDouble(derivativesData.get("longPct"));
        if (longPct != null) {
            if (longPct > 65) {
                score -= 30;
                reasons.add(String.format("%.0f%% long — crowded longs (bearish)", longPct));
            } else if (longPct < 35) {
                score += 30;
                reasons.add(String.format("%.0f%% long — crowded shorts (bullish)", longPct));
            } else {
                reasons.add(String.format("%.0f%% long — balanced positioning", longPct));
            }
        }

        // Open interest confirmation
        Double oiUsd = asDouble(derivativesData.get("openInterestUsd"));
        if (oiUsd != null && oiUsd > 0) {
            // OI growing is a trend confirmation — add directional bias
            score += (score > 0) ? 20 : (score < 0) ? -20 : 0;
            if (score != 0) {
                reasons.add("Open interest confirms trend direction");
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
                // sentimentScore is typically -1 to +1, scale to -50 to +50
                score += sentimentScore * 50;
                reasons.add(String.format("News sentiment %.2f — %s", sentimentScore,
                        sentimentScore > 0.3 ? "positive" : sentimentScore < -0.3 ? "negative" : "neutral"));
            }
        }

        // Fear & Greed — contrarian
        if (macroData != null) {
            // Look in market overview first (passed via macroData)
            Integer fearGreed = asInt(macroData.get("fearGreedIndex"));
            if (fearGreed != null) {
                if (fearGreed < 25) {
                    score += 40;
                    reasons.add(String.format("Fear & Greed at %d — extreme fear (contrarian buy)", fearGreed));
                } else if (fearGreed > 75) {
                    score -= 40;
                    reasons.add(String.format("Fear & Greed at %d — extreme greed (contrarian sell)", fearGreed));
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
        // Use long/short ratio as order book proxy since we don't have direct bid/ask
        if (derivativesData == null) {
            return new DimensionScore("Order Book", 0, WEIGHT_ORDER_BOOK, List.of("No order book data available"));
        }

        List<String> reasons = new ArrayList<>();
        Double longPct = asDouble(derivativesData.get("longPct"));
        Double shortPct = asDouble(derivativesData.get("shortPct"));

        if (longPct == null || shortPct == null) {
            return new DimensionScore("Order Book", 0, WEIGHT_ORDER_BOOK, List.of("Order book data unavailable"));
        }

        // Imbalance: positive = more buying pressure, negative = more selling pressure
        double imbalance = longPct - shortPct;
        double score = clamp(imbalance * 2, -100, 100);

        reasons.add(String.format("Long/short imbalance %+.1f%% — %s",
                imbalance,
                imbalance > 10 ? "buy-side pressure" : imbalance < -10 ? "sell-side pressure" : "balanced"));

        return new DimensionScore("Order Book", score, WEIGHT_ORDER_BOOK, reasons);
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
            // BTC dominance rising = BTC bullish, altcoins bearish
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
            // Higher stablecoin cap = more dry powder = bullish
            // We don't have historical data to compare, so use a simple heuristic
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

    private int computeConfidence(List<DimensionScore> dimensions, double overallScore) {
        if (overallScore == 0) return 30;

        boolean isPositive = overallScore > 0;
        int aligned = 0;
        for (DimensionScore dim : dimensions) {
            if ((isPositive && dim.score() > 0) || (!isPositive && dim.score() < 0)) {
                aligned++;
            }
        }

        return switch (aligned) {
            case 6 -> 100;
            case 5 -> 85;
            case 4 -> 70;
            case 3 -> 50;
            default -> 30;
        };
    }

    private String determineSignalLabel(double score, int confidence) {
        if (score >= 60 && confidence >= 70) return STRONG_BUY;
        if (score >= 30 && confidence >= 50) return BUY;
        if (score <= -60 && confidence >= 70) return STRONG_SELL;
        if (score <= -30 && confidence >= 50) return SELL;
        return NEUTRAL;
    }

    private String determineAlertLevel(String signalLabel, int confidence) {
        if ((STRONG_BUY.equals(signalLabel) || STRONG_SELL.equals(signalLabel)) && confidence >= 70) {
            return "OPPORTUNITY";
        }
        if ((BUY.equals(signalLabel) || SELL.equals(signalLabel)) && confidence >= 50) {
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

        String signalLabel = signal.getSignal();
        if (BUY.equals(signalLabel) || STRONG_BUY.equals(signalLabel)) {
            signal.setSuggestedEntry(price);
            if (support != null && atr != null && support > 0 && atr > 0) {
                signal.setSuggestedStopLoss(Math.round((support - atr) * 100.0) / 100.0);
            }
            if (resistance != null && resistance > 0) {
                signal.setSuggestedTakeProfit(Math.round(resistance * 100.0) / 100.0);
            }
        } else if (SELL.equals(signalLabel) || STRONG_SELL.equals(signalLabel)) {
            signal.setSuggestedEntry(price);
            if (resistance != null && atr != null && resistance > 0 && atr > 0) {
                signal.setSuggestedStopLoss(Math.round((resistance + atr) * 100.0) / 100.0);
            }
            if (support != null && support > 0) {
                signal.setSuggestedTakeProfit(Math.round(support * 100.0) / 100.0);
            }
        }

        computeRiskReward(signal);
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
            signal.setRiskRewardRatio(Math.round((reward / risk) * 100.0) / 100.0);
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
