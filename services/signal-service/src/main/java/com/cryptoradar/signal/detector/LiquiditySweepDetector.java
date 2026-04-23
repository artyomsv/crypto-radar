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

    /**
     * The "sweep" wick must pierce the level by at least this × ATR to matter.
     * Tightened from 0.1 to 0.3 after outcome analysis: a wick that only pokes
     * through the swing level by a tenth of an ATR is indistinguishable from
     * ordinary intrabar jitter. A quarter-ATR minimum weeds out noise without
     * rejecting real stop-hunts (which typically overshoot by 0.5-1.0 ATR).
     */
    private static final double MIN_PIERCE_ATR_FRACTION = 0.3;

    /** Rejection wick must be this fraction of the bar body to qualify. */
    private static final double MIN_WICK_BODY_RATIO = 0.5;

    /**
     * Close must reclaim the swept level by at least this fraction of the bar's
     * body. A close one cent above the swing is a failed retest, not a
     * rejection — require meaningful re-entry into the prior range.
     */
    private static final double MIN_RECLAIM_BODY_RATIO = 0.3;

    /**
     * Skip detection entirely when ATR/price is below this fraction. Range-bound
     * or illiquid symbols produce wicks that match the sweep pattern but carry
     * no directional information. LTC in low-ATR regimes was producing 46 of 54
     * LS signals by this mechanism.
     */
    private static final double MIN_ATR_PCT = 0.003;

    /**
     * Entry is only valid if price hasn't drifted more than this from the trigger
     * close. Tightened from 1.5 to 0.5: a 1.5% drift after a reversal trigger
     * often means half the reversal move has already happened, so the entry R:R
     * degrades significantly. Tighter window = we enter closer to the sweep low.
     */
    private static final double MAX_DRIFT_PCT = 0.5;

    /**
     * Derivatives dimension must not oppose the reversal by more than this.
     * Tightened from 15 to 5: the contrarian thesis of a liquidity sweep depends
     * on crowded positioning to be wrong. Even a mild deriv reading opposite the
     * reversal direction invalidates the thesis.
     */
    private static final double DIM_DERIVATIVES_TOLERANCE = 5.0;

    /**
     * Widened from 0.2 to 0.5 after outcome analysis: 23 of 53 stops had MAE ≥ 1.5R,
     * meaning price pierced the stop by a substantial margin then reversed. A tighter
     * buffer was inside normal post-sweep retest range.
     */
    private static final double STOP_BUFFER_ATR = 0.5;

    private static final double TARGET_R_MULTIPLE = 5.0;
    private static final int STRONG_SIGNAL_ALIGNMENT = 70;

    /**
     * LS-specific minimum stop distance. Protects against LTC-class contamination:
     * when swing-low wick sits essentially at entry in a low-ATR regime, the
     * wick-referenced stop would sit inside the bid-ask spread. 46 of 54 LS trades
     * in the 21-day window were LTC signals with avg risk 0.035% — phantom trades
     * that stopped out instantly.
     *
     * <p>Aligned with {@code SignalEngine.MIN_RISK_PCT} for fee-drag reasons:
     * 0.11% round-trip taker fees vs a 0.5% stop = 22% drag per 1R. Widening to
     * 1.5% cuts that to ~7% — the operating band we ship to execution.
     */
    private static final double LS_MIN_RISK_PCT = 0.015;

    /**
     * Trigger-bar volume must be at least this ratio of the prior 3 bars'
     * average volume to qualify. Genuine liquidity sweeps run stop orders →
     * volume spike; quiet wicks with normal volume are indistinguishable
     * from random retests.
     *
     * <p>Degrades gracefully when volume data is missing (bar volume = 0):
     * the filter is skipped rather than rejecting the signal. New CandleBars
     * via the legacy 5-argument constructor default to {@code volume == 0}.
     */
    private static final double MIN_VOLUME_RATIO = 1.3;

    /** Number of prior bars used to compute the volume baseline. */
    private static final int VOLUME_BASELINE_BARS = 3;

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

        // Low-ATR regime guard. Below this threshold, swing levels sit inside the
        // usual intrabar noise and the sweep pattern carries no reversal signal.
        if (atr14 / context.currentPrice() < MIN_ATR_PCT) return Optional.empty();

        CandleBar trigger = bars.get(bars.size() - 2);  // last *closed* bar
        List<CandleBar> swingBars = bars.subList(0, bars.size() - 2);
        double swingHigh = swingHigh(swingBars);
        double swingLow = swingLow(swingBars);

        String direction = classifySweep(trigger, swingHigh, swingLow, atr14);
        if (direction == null) return Optional.empty();

        double driftPct = Math.abs(context.currentPrice() - trigger.close()) / trigger.close() * 100.0;
        if (driftPct > MAX_DRIFT_PCT) return Optional.empty();

        if (!confluenceAgrees(direction, context)) return Optional.empty();

        if (!hasVolumeConfirmation(bars, trigger)) return Optional.empty();

        return Optional.of(buildSetup(context, direction, trigger, atr14, swingHigh, swingLow));
    }

    /**
     * True if the trigger bar's volume is at least {@link #MIN_VOLUME_RATIO}
     * times the average of the prior {@link #VOLUME_BASELINE_BARS} closed
     * bars. Degrades to {@code true} when volume data is missing so legacy
     * pipelines don't silently reject every signal.
     */
    private boolean hasVolumeConfirmation(List<CandleBar> bars, CandleBar trigger) {
        if (trigger.volume() <= 0) return true;   // no volume data → skip filter
        int triggerIdx = bars.size() - 2;
        if (triggerIdx < VOLUME_BASELINE_BARS) return true;   // not enough history

        double sum = 0;
        int counted = 0;
        for (int i = triggerIdx - VOLUME_BASELINE_BARS; i < triggerIdx; i++) {
            double v = bars.get(i).volume();
            if (v > 0) {
                sum += v;
                counted++;
            }
        }
        if (counted == 0) return true;   // no valid baseline → skip filter
        double avg = sum / counted;
        return trigger.volume() >= avg * MIN_VOLUME_RATIO;
    }

    private String classifySweep(CandleBar trigger, double swingHigh, double swingLow, double atr) {
        boolean isGreen = trigger.close() > trigger.open();
        if (isBullishSweep(trigger, swingLow, atr) && isGreen) return DIRECTION_LONG;
        if (isBearishSweep(trigger, swingHigh, atr) && !isGreen) return DIRECTION_SHORT;
        return null;
    }

    private boolean isBullishSweep(CandleBar trigger, double swingLow, double atr) {
        double body = Math.abs(trigger.close() - trigger.open());
        boolean pierced = trigger.low() < swingLow - (atr * MIN_PIERCE_ATR_FRACTION);
        // Close must sit meaningfully inside the prior range — a reclaim by
        // one tick is a failed retest, not a rejection.
        boolean reclaimed = trigger.close() > swingLow + body * MIN_RECLAIM_BODY_RATIO;
        double lowerWick = Math.min(trigger.open(), trigger.close()) - trigger.low();
        boolean rejectionWick = body > 0 && lowerWick >= body * MIN_WICK_BODY_RATIO;
        return pierced && reclaimed && rejectionWick;
    }

    private boolean isBearishSweep(CandleBar trigger, double swingHigh, double atr) {
        double body = Math.abs(trigger.close() - trigger.open());
        boolean pierced = trigger.high() > swingHigh + (atr * MIN_PIERCE_ATR_FRACTION);
        boolean reclaimed = trigger.close() < swingHigh - body * MIN_RECLAIM_BODY_RATIO;
        double upperWick = trigger.high() - Math.max(trigger.open(), trigger.close());
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

        int alignment = computeAlignment(context, trigger, isLong);
        String signalType = mapSignalType(direction, alignment);
        List<String> reasons = buildReasons(direction, trigger, swingHigh, swingLow, atr14, context);

        return new TradeSetup(NAME, context.symbol(), direction, signalType,
                entry, stop, target, rr, alignment, reasons, Instant.now());
    }

    private int computeAlignment(MarketContext context, CandleBar trigger, boolean isLong) {
        double derivScore = ContextValues.dimensionScore(context.dimensionScores(), "Derivatives");
        double whaleScore = ContextValues.dimensionScore(context.dimensionScores(), "Whale");
        double orderBookScore = ContextValues.dimensionScore(context.dimensionScores(), "Order Book");

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

    private String mapSignalType(String direction, int alignment) {
        boolean isLong = DIRECTION_LONG.equals(direction);
        if (alignment >= STRONG_SIGNAL_ALIGNMENT) return isLong ? "STRONG_BUY" : "STRONG_SELL";
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
