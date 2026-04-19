package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.MarketContext;
import com.cryptoradar.signal.model.TradeSetup;
import com.cryptoradar.signal.util.ContextValues;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects trend-continuation entries: established HTF trend + healthy
 * pullback to mid-term moving average + dimension confluence not opposing.
 *
 * <p>This is deliberately a "boring but reliable" detector. It rejects
 * anything that isn't clearly trending, pulled back by a meaningful amount
 * without breaking the trend structure, and supported by non-opposing
 * derivatives/whale flows. The goal is high specificity, not sensitivity.
 */
@ApplicationScoped
public class TrendContinuationDetector implements TradeSetupDetector {

    private static final String NAME = "trend-continuation";
    private static final String DIRECTION_LONG = "LONG";
    private static final String DIRECTION_SHORT = "SHORT";

    // Pullback zone: too shallow = not a real pullback, too deep = trend break.
    private static final double MIN_PULLBACK_PCT = 0.3;
    private static final double MAX_PULLBACK_PCT = 2.0;
    private static final double IDEAL_PULLBACK_MIN = 0.5;
    private static final double IDEAL_PULLBACK_MAX = 1.5;

    // RSI guard: avoid both overbought tops and panic bottoms.
    private static final double RSI_MIN = 35.0;
    private static final double RSI_MAX = 65.0;

    // Dimension confluence: require non-opposing cross-domain agreement.
    private static final double DIM_TECHNICAL_MIN = 20.0;
    private static final double DIM_DERIVATIVES_TOLERANCE = 15.0;
    private static final double DIM_WHALE_TOLERANCE = 20.0;

    // Trade levels.
    private static final double STOP_ATR_MULTIPLE = 1.5;
    private static final double TARGET_R_MULTIPLE = 5.0;
    private static final int STRONG_SIGNAL_CONFIDENCE = 65;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<TradeSetup> detect(MarketContext context) {
        Inputs in = Inputs.from(context);
        if (!in.isComplete()) return Optional.empty();

        String direction = resolveDirection(in);
        if (direction == null) return Optional.empty();

        double pullbackPct = Math.abs(in.price - in.sma20) / in.price * 100.0;
        if (pullbackPct < MIN_PULLBACK_PCT || pullbackPct > MAX_PULLBACK_PCT) return Optional.empty();
        if (in.rsi14 < RSI_MIN || in.rsi14 > RSI_MAX) return Optional.empty();

        if (!confluenceAgrees(direction, in)) return Optional.empty();

        return Optional.of(buildSetup(context.symbol(), direction, in, pullbackPct));
    }

    private String resolveDirection(Inputs in) {
        boolean uptrend = in.sma50 > in.sma200 && in.price > in.sma50;
        boolean downtrend = in.sma50 < in.sma200 && in.price < in.sma50;
        if (uptrend) return DIRECTION_LONG;
        if (downtrend) return DIRECTION_SHORT;
        return null;
    }

    private boolean confluenceAgrees(String direction, Inputs in) {
        boolean isLong = DIRECTION_LONG.equals(direction);
        double techExpected = isLong ? in.technicalScore : -in.technicalScore;
        if (techExpected < DIM_TECHNICAL_MIN) return false;

        double derivOpposition = isLong ? -in.derivativesScore : in.derivativesScore;
        if (derivOpposition > DIM_DERIVATIVES_TOLERANCE) return false;

        double whaleOpposition = isLong ? -in.whaleScore : in.whaleScore;
        return whaleOpposition <= DIM_WHALE_TOLERANCE;
    }

    private TradeSetup buildSetup(String symbol, String direction, Inputs in, double pullbackPct) {
        boolean isLong = DIRECTION_LONG.equals(direction);
        double risk = in.atr14 * STOP_ATR_MULTIPLE;
        double entry = in.price;
        double stop = isLong ? entry - risk : entry + risk;
        double structuralTarget = isLong ? in.resistance : in.support;
        double rMultipleTarget = isLong ? entry + risk * TARGET_R_MULTIPLE
                                        : entry - risk * TARGET_R_MULTIPLE;
        double target = isLong ? Math.max(rMultipleTarget, structuralTarget)
                                : Math.min(rMultipleTarget, structuralTarget);
        double rr = isLong ? (target - entry) / risk : (entry - target) / risk;

        int confidence = computeConfidence(in, pullbackPct);
        String signalType = mapSignalType(direction, confidence);
        List<String> reasons = buildReasons(direction, in, pullbackPct);

        return new TradeSetup(NAME, symbol, direction, signalType,
                entry, stop, target, rr, confidence, reasons, Instant.now());
    }

    private int computeConfidence(Inputs in, double pullbackPct) {
        int score = 40;
        if (Math.abs(in.technicalScore) >= 60) score += 15;
        else if (Math.abs(in.technicalScore) >= 40) score += 10;
        if (Math.abs(in.derivativesScore) >= 40) score += 10;
        else if (Math.abs(in.derivativesScore) >= 15) score += 5;
        if (Math.abs(in.whaleScore) >= 30) score += 10;
        else if (Math.abs(in.whaleScore) >= 10) score += 5;
        if (pullbackPct >= IDEAL_PULLBACK_MIN && pullbackPct <= IDEAL_PULLBACK_MAX) score += 5;
        return Math.min(95, score);
    }

    private String mapSignalType(String direction, int confidence) {
        boolean isLong = DIRECTION_LONG.equals(direction);
        if (confidence >= STRONG_SIGNAL_CONFIDENCE) return isLong ? "STRONG_BUY" : "STRONG_SELL";
        return isLong ? "BUY" : "SELL";
    }

    private List<String> buildReasons(String direction, Inputs in, double pullbackPct) {
        List<String> reasons = new ArrayList<>();
        boolean isLong = DIRECTION_LONG.equals(direction);
        reasons.add(isLong
                ? String.format("HTF uptrend (SMA50 %.2f > SMA200 %.2f, price %.2f > SMA50)",
                        in.sma50, in.sma200, in.price)
                : String.format("HTF downtrend (SMA50 %.2f < SMA200 %.2f, price %.2f < SMA50)",
                        in.sma50, in.sma200, in.price));
        reasons.add(String.format("Pullback to SMA20 (%.2f%% away, RSI %.1f)", pullbackPct, in.rsi14));
        reasons.add(String.format("Dimension confluence (tech %.0f, deriv %.0f, whale %.0f)",
                in.technicalScore, in.derivativesScore, in.whaleScore));
        return reasons;
    }

    /**
     * Bag of extracted, type-checked inputs. Kept as a private static class
     * so the main detection flow stays linear.
     */
    private static final class Inputs {
        double price, sma20, sma50, sma200, atr14, rsi14;
        double support, resistance;
        double technicalScore, derivativesScore, whaleScore;
        boolean complete;

        boolean isComplete() { return complete; }

        static Inputs from(MarketContext context) {
            Inputs in = new Inputs();
            Map<String, Object> indicators = ContextValues.asMap(context.analytics().get("technicalIndicators"));
            Double price = context.currentPrice();
            Double sma20  = ContextValues.readDouble(indicators, "sma20");
            Double sma50  = ContextValues.readDouble(indicators, "sma50");
            Double sma200 = ContextValues.readDouble(indicators, "sma200");
            Double atr14  = ContextValues.readDouble(indicators, "atr14");
            Double rsi14  = ContextValues.readDouble(indicators, "rsi14");
            Double support    = ContextValues.readDouble(context.analytics(), "supportLevel");
            Double resistance = ContextValues.readDouble(context.analytics(), "resistanceLevel");

            if (price == null || sma20 == null || sma50 == null || sma200 == null
                    || atr14 == null || rsi14 == null || support == null || resistance == null
                    || atr14 <= 0) {
                in.complete = false;
                return in;
            }
            in.price = price; in.sma20 = sma20; in.sma50 = sma50; in.sma200 = sma200;
            in.atr14 = atr14; in.rsi14 = rsi14;
            in.support = support; in.resistance = resistance;
            in.technicalScore   = ContextValues.dimensionScore(context.dimensionScores(), "Technical");
            in.derivativesScore = ContextValues.dimensionScore(context.dimensionScores(), "Derivatives");
            in.whaleScore       = ContextValues.dimensionScore(context.dimensionScores(), "Whale");
            in.complete = true;
            return in;
        }
    }
}
