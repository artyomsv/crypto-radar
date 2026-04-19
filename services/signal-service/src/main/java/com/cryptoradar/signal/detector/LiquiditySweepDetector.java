package com.cryptoradar.signal.detector;

import com.cryptoradar.signal.model.CandleBar;
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
 * Detects stop-hunt reversal setups on the 4h timeframe.
 *
 * <p>A "liquidity sweep" is the pattern where price briefly pierces a prior
 * swing high or low (triggering retail stop-losses and forced liquidations),
 * then reverses back into the range. The setup trades AGAINST the sweep,
 * betting that the move was manipulation rather than real breakout.
 *
 * <p>This detector is intentionally conservative: it fires only when the
 * last closed 4h bar both pierces a recent swing by a meaningful fraction
 * of ATR AND closes back inside the range with a rejection wick. This is
 * the specific kind of setup the project's unique data stack (live
 * liquidation feed, multi-exchange orderbook depth) can best filter.
 */
@ApplicationScoped
public class LiquiditySweepDetector implements TradeSetupDetector {

    private static final String NAME = "liquidity-sweep";
    private static final String DIRECTION_LONG = "LONG";
    private static final String DIRECTION_SHORT = "SHORT";

    /** Minimum 4h bars required: enough swing history plus one trigger bar. */
    private static final int MIN_BARS = 8;

    /** The "sweep" wick must pierce the level by at least this × ATR to matter. */
    private static final double MIN_PIERCE_ATR_FRACTION = 0.1;

    /** Rejection wick must be this fraction of the bar body to qualify. */
    private static final double MIN_WICK_BODY_RATIO = 0.5;

    /** Entry is only valid if price hasn't drifted more than this from the trigger close. */
    private static final double MAX_DRIFT_PCT = 1.5;

    /** Derivatives dimension must not oppose the reversal by more than this. */
    private static final double DIM_DERIVATIVES_TOLERANCE = 15.0;

    /**
     * Widened from 0.2 to 0.5 after outcome analysis: 23 of 53 stops had MAE ≥ 1.5R,
     * meaning price pierced the stop by a substantial margin then reversed. A tighter
     * buffer was inside normal post-sweep retest range.
     */
    private static final double STOP_BUFFER_ATR = 0.5;

    private static final double TARGET_R_MULTIPLE = 5.0;
    private static final int STRONG_SIGNAL_CONFIDENCE = 70;

    /**
     * LS-specific minimum stop distance. Protects against LTC-class contamination:
     * when swing-low wick sits essentially at entry in a low-ATR regime, the
     * wick-referenced stop would sit inside the bid-ask spread. 46 of 54 LS trades
     * in the 21-day window were LTC signals with avg risk 0.035% — phantom trades
     * that stopped out instantly.
     */
    private static final double LS_MIN_RISK_PCT = 0.005;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<TradeSetup> detect(MarketContext context) {
        List<CandleBar> bars = context.recent4hBars();
        if (bars == null || bars.size() < MIN_BARS) return Optional.empty();
        if (context.currentPrice() == null) return Optional.empty();

        Double atr14 = ContextValues.readDouble(
                ContextValues.asMap(context.analytics().get("technicalIndicators")), "atr14");
        if (atr14 == null || atr14 <= 0) return Optional.empty();

        CandleBar trigger = bars.get(bars.size() - 2);  // last *closed* bar
        List<CandleBar> swingBars = bars.subList(0, bars.size() - 2);
        double swingHigh = swingHigh(swingBars);
        double swingLow = swingLow(swingBars);

        String direction = classifySweep(trigger, swingHigh, swingLow, atr14);
        if (direction == null) return Optional.empty();

        double driftPct = Math.abs(context.currentPrice() - trigger.close()) / trigger.close() * 100.0;
        if (driftPct > MAX_DRIFT_PCT) return Optional.empty();

        if (!confluenceAgrees(direction, context)) return Optional.empty();

        return Optional.of(buildSetup(context, direction, trigger, atr14, swingHigh, swingLow));
    }

    private String classifySweep(CandleBar trigger, double swingHigh, double swingLow, double atr) {
        boolean isGreen = trigger.close() > trigger.open();
        if (isBullishSweep(trigger, swingLow, atr) && isGreen) return DIRECTION_LONG;
        if (isBearishSweep(trigger, swingHigh, atr) && !isGreen) return DIRECTION_SHORT;
        return null;
    }

    private boolean isBullishSweep(CandleBar trigger, double swingLow, double atr) {
        boolean pierced = trigger.low() < swingLow - (atr * MIN_PIERCE_ATR_FRACTION);
        boolean reclaimed = trigger.close() > swingLow;
        double lowerWick = Math.min(trigger.open(), trigger.close()) - trigger.low();
        double body = Math.abs(trigger.close() - trigger.open());
        boolean rejectionWick = body > 0 && lowerWick >= body * MIN_WICK_BODY_RATIO;
        return pierced && reclaimed && rejectionWick;
    }

    private boolean isBearishSweep(CandleBar trigger, double swingHigh, double atr) {
        boolean pierced = trigger.high() > swingHigh + (atr * MIN_PIERCE_ATR_FRACTION);
        boolean reclaimed = trigger.close() < swingHigh;
        double upperWick = trigger.high() - Math.max(trigger.open(), trigger.close());
        double body = Math.abs(trigger.close() - trigger.open());
        boolean rejectionWick = body > 0 && upperWick >= body * MIN_WICK_BODY_RATIO;
        return pierced && reclaimed && rejectionWick;
    }

    private boolean confluenceAgrees(String direction, MarketContext context) {
        double derivScore = ContextValues.dimensionScore(context.dimensionScores(), "Derivatives");
        boolean isLong = DIRECTION_LONG.equals(direction);
        double derivOpposition = isLong ? -derivScore : derivScore;
        return derivOpposition <= DIM_DERIVATIVES_TOLERANCE;
    }

    private TradeSetup buildSetup(MarketContext context, String direction,
                                  CandleBar trigger, double atr14,
                                  double swingHigh, double swingLow) {
        boolean isLong = DIRECTION_LONG.equals(direction);
        double entry = context.currentPrice();
        double stop = isLong
                ? trigger.low() - (atr14 * STOP_BUFFER_ATR)
                : trigger.high() + (atr14 * STOP_BUFFER_ATR);
        stop = enforceMinRiskDistance(entry, stop, isLong);
        double risk = Math.abs(entry - stop);
        double rMultipleTarget = isLong ? entry + risk * TARGET_R_MULTIPLE
                                        : entry - risk * TARGET_R_MULTIPLE;
        double structuralTarget = isLong ? swingHigh : swingLow;
        double target = isLong ? Math.max(rMultipleTarget, structuralTarget)
                                : Math.min(rMultipleTarget, structuralTarget);
        double rr = risk > 0 ? Math.abs(target - entry) / risk : 0.0;

        int confidence = computeConfidence(context, trigger, isLong);
        String signalType = mapSignalType(direction, confidence);
        List<String> reasons = buildReasons(direction, trigger, swingHigh, swingLow, atr14, context);

        return new TradeSetup(NAME, context.symbol(), direction, signalType,
                entry, stop, target, rr, confidence, reasons, Instant.now());
    }

    private int computeConfidence(MarketContext context, CandleBar trigger, boolean isLong) {
        double derivScore = ContextValues.dimensionScore(context.dimensionScores(), "Derivatives");
        double whaleScore = ContextValues.dimensionScore(context.dimensionScores(), "Whale");
        double orderBookScore = ContextValues.dimensionScore(context.dimensionScores(), "OrderBook");

        int score = 50;
        double directed = isLong ? 1.0 : -1.0;
        if (derivScore * directed >= 30) score += 15;
        else if (derivScore * directed >= 10) score += 8;
        if (whaleScore * directed >= 20) score += 10;
        else if (whaleScore * directed >= 5) score += 5;
        if (orderBookScore * directed >= 20) score += 10;

        double body = Math.abs(trigger.close() - trigger.open());
        double fullRange = trigger.high() - trigger.low();
        if (fullRange > 0 && body / fullRange < 0.4) score += 5;  // small body, big wicks = strong rejection

        return Math.min(95, score);
    }

    private String mapSignalType(String direction, int confidence) {
        boolean isLong = DIRECTION_LONG.equals(direction);
        if (confidence >= STRONG_SIGNAL_CONFIDENCE) return isLong ? "STRONG_BUY" : "STRONG_SELL";
        return isLong ? "BUY" : "SELL";
    }

    private List<String> buildReasons(String direction, CandleBar trigger,
                                      double swingHigh, double swingLow, double atr,
                                      MarketContext context) {
        List<String> reasons = new ArrayList<>();
        boolean isLong = DIRECTION_LONG.equals(direction);
        double sweptLevel = isLong ? swingLow : swingHigh;
        double pierceDepth = isLong ? swingLow - trigger.low() : trigger.high() - swingHigh;
        reasons.add(String.format("%s sweep of %.2f (pierce %.2f ≈ %.2f ATR)",
                isLong ? "Bullish" : "Bearish", sweptLevel, pierceDepth, pierceDepth / atr));
        reasons.add(String.format("Rejection close back inside range at %.2f", trigger.close()));
        reasons.add(String.format("Derivatives confluence (score %.0f)",
                ContextValues.dimensionScore(context.dimensionScores(), "Derivatives")));
        return reasons;
    }

    /**
     * Widens the stop to at least {@link #LS_MIN_RISK_PCT} of entry price. Returns
     * the stop unchanged if it already sits beyond that floor.
     */
    private double enforceMinRiskDistance(double entry, double stop, boolean isLong) {
        double actualRisk = Math.abs(entry - stop);
        double minRisk = entry * LS_MIN_RISK_PCT;
        if (actualRisk >= minRisk) return stop;
        return isLong ? entry - minRisk : entry + minRisk;
    }

    private double swingHigh(List<CandleBar> bars) {
        double high = Double.NEGATIVE_INFINITY;
        for (CandleBar bar : bars) if (bar.high() > high) high = bar.high();
        return high;
    }

    private double swingLow(List<CandleBar> bars) {
        double low = Double.POSITIVE_INFINITY;
        for (CandleBar bar : bars) if (bar.low() < low) low = bar.low();
        return low;
    }
}
