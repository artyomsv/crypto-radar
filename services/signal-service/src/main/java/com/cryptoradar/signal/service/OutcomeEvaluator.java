package com.cryptoradar.signal.service;

import com.cryptoradar.core.TrailCalculator;
import com.cryptoradar.core.TrailConfig;
import com.cryptoradar.signal.model.CandleBar;
import com.cryptoradar.signal.model.OutcomeStatus;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every minute, walks forward through the latest 1m candles of each PENDING
 * signal and checks whether the trade hit stop, target, or should be expired.
 *
 * <p>This is the other half of the feedback loop: {@code OutcomeTracker}
 * records what the engine predicted, and this class records what actually
 * happened. Together they enable after-the-fact measurement of signal quality.
 */
@ApplicationScoped
public class OutcomeEvaluator {

    private static final Logger LOG = Logger.getLogger(OutcomeEvaluator.class);

    private static final String INTERVAL_1M = "1m";
    private static final int CANDLE_FETCH_LIMIT = 1500;
    private static final Duration MAX_HOLD = Duration.ofDays(7);
    private static final String DIRECTION_LONG = "LONG";

    private static final String EXIT_TARGET = "TARGET";
    private static final String EXIT_INITIAL_STOP = "INITIAL_STOP";
    private static final String EXIT_TRAIL_STOP = "TRAIL_STOP";
    private static final String EXIT_EXPIRED = "EXPIRED";

    private final SignalOutcomeRepository repository;
    private final CandleClient candleClient;

    public OutcomeEvaluator(SignalOutcomeRepository repository, CandleClient candleClient) {
        this.repository = repository;
        this.candleClient = candleClient;
    }

    @Scheduled(every = "60s", delayed = "30s")
    @Transactional
    public void evaluatePending() {
        List<SignalOutcome> pending = repository.findPending();
        if (pending.isEmpty()) return;

        int closed = 0;
        for (SignalOutcome outcome : pending) {
            boolean didClose = evaluateOne(outcome);
            if (didClose) closed++;
        }
        LOG.debugf("Evaluated %d pending outcomes, closed %d", pending.size(), closed);
    }

    /**
     * Evaluates a single pending outcome against the latest 1m candles.
     * Returns {@code true} if the outcome transitioned to a terminal state.
     */
    private boolean evaluateOne(SignalOutcome outcome) {
        List<CandleBar> bars = candleClient.fetchRecent(outcome.getSymbol(), INTERVAL_1M, CANDLE_FETCH_LIMIT);
        if (bars.isEmpty()) return false;

        Instant scanAfter = resolveScanStart(outcome);
        for (CandleBar bar : bars) {
            if (!bar.time().isAfter(scanAfter)) continue;
            updateExcursions(outcome, bar);
            updateTrailingStop(outcome, bar);
            OutcomeStatus hit = detectHit(outcome, bar);
            if (hit != null) {
                closeOutcome(outcome, hit, bar.time(), priceFor(outcome, hit));
                return true;
            }
        }

        outcome.setLastEvaluatedAt(bars.get(bars.size() - 1).time());
        return expireIfStale(outcome, bars);
    }

    private Instant resolveScanStart(SignalOutcome outcome) {
        Instant last = outcome.getLastEvaluatedAt();
        return last != null ? last : outcome.getFiredAt();
    }

    // Package-private for unit tests — exercises the stop/target detection
    // logic directly without needing to stub the repository or candle client.
    OutcomeStatus detectHit(SignalOutcome outcome, CandleBar bar) {
        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
        double effectiveStop = effectiveStop(outcome, isLong);

        boolean stopHit = isLong
                ? bar.low() <= effectiveStop
                : bar.high() >= effectiveStop;
        boolean targetHit = isLong
                ? bar.high() >= outcome.getTargetPrice()
                : bar.low() <= outcome.getTargetPrice();

        // Intra-bar ordering is unknowable from OHLC alone. Two policies:
        //   1. Trail inactive → pessimistic (stop first). Matches historical convention.
        //   2. Trail active → optimistic target-first. Reasoning: the trail level was
        //      ratcheted by a prior MFE excursion that already passed this bar's target
        //      threshold — if the trade had range to retrace to trail, it had range
        //      to print target first on the way down.
        boolean trailActive = outcome.getDynamicStopPrice() != null;
        if (targetHit && trailActive) return OutcomeStatus.HIT_TARGET;
        if (stopHit) return OutcomeStatus.HIT_STOP;
        if (targetHit) return OutcomeStatus.HIT_TARGET;
        return null;
    }

    /**
     * Current stop price to compare candles against: the tighter of the initial
     * stop and any ratcheted dynamic stop.
     */
    private double effectiveStop(SignalOutcome outcome, boolean isLong) {
        Double dyn = outcome.getDynamicStopPrice();
        if (dyn == null) return outcome.getStopPrice();
        return isLong
                ? Math.max(outcome.getStopPrice(), dyn)
                : Math.min(outcome.getStopPrice(), dyn);
    }

    private double priceFor(SignalOutcome outcome, OutcomeStatus terminal) {
        if (terminal == OutcomeStatus.HIT_TARGET) return outcome.getTargetPrice();
        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
        return effectiveStop(outcome, isLong);
    }

    /**
     * Ratchets the trailing stop when the latest bar's favorable excursion
     * advances past the next rung. Trail logic:
     *
     * <pre>{@code
     *   mfe_r = (best_price - entry) / risk            (LONG; flip sign for SHORT)
     *   if mfe_r < activation_r: do nothing
     *   rung     = floor((mfe_r - activation_r) / step_r)
     *   stop_r   = activation_r + rung * step_r - offset_r
     *   stop$    = entry + stop_r * risk               (LONG; subtract for SHORT)
     * }</pre>
     *
     * <p>The trail is monotonic — it never loosens, so a subsequent pull-back
     * below the current rung doesn't reset it.
     */
    // Package-private for unit tests — see detectHit note above.
    void updateTrailingStop(SignalOutcome outcome, CandleBar bar) {
        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
        double entry = outcome.getEntryPrice();
        double risk = Math.abs(entry - outcome.getStopPrice());
        if (risk <= 0) return;

        // Use cumulative MFE so backfill (historical peak) and pull-backs (peak
        // already past) both work on the first evaluator tick.
        double riskPct = risk / entry * 100.0;
        if (riskPct <= 0) return;
        double mfeR = outcome.getMaxFavorablePct() / riskPct;

        TrailConfig config = new TrailConfig(
                outcome.getTrailActivationR(),
                outcome.getTrailStepR(),
                outcome.getTrailOffsetR());

        Optional<Double> newTrailR = TrailCalculator.computeNewTrailR(
                mfeR, config, outcome.getTrailHighestR());
        if (newTrailR.isEmpty()) return;

        double newR = newTrailR.get();
        outcome.setTrailHighestR(newR);
        double newStop = isLong ? entry + newR * risk : entry - newR * risk;
        outcome.setDynamicStopPrice(newStop);
        if (outcome.getTrailTriggeredAt() == null) {
            outcome.setTrailTriggeredAt(bar.time());
            LOG.infof("TRAIL activated %s %s at rung %.2fR → stop=%.4f",
                    outcome.getSymbol(), outcome.getDirection(), newR, newStop);
        }
    }

    // Package-private for unit tests — see updateTrailingStop note.
    void updateExcursions(SignalOutcome outcome, CandleBar bar) {
        double entry = outcome.getEntryPrice();
        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());

        double bestPrice = isLong ? bar.high() : bar.low();
        double worstPrice = isLong ? bar.low() : bar.high();

        double mfeCandidate = pctMove(entry, bestPrice, isLong);
        double maeCandidate = pctMove(entry, worstPrice, isLong);

        if (mfeCandidate > outcome.getMaxFavorablePct()) {
            outcome.setMaxFavorablePct(mfeCandidate);
            outcome.setTimeToMfeSeconds(secondsFromFire(outcome, bar));
        }
        if (maeCandidate < outcome.getMaxAdversePct()) {
            outcome.setMaxAdversePct(maeCandidate);
            outcome.setTimeToMaeSeconds(secondsFromFire(outcome, bar));
        }
    }

    /**
     * Elapsed seconds between signal fire and the supplied bar. Cast to int is
     * safe because even the max hold window (7 days) fits comfortably.
     */
    private int secondsFromFire(SignalOutcome outcome, CandleBar bar) {
        return (int) Duration.between(outcome.getFiredAt(), bar.time()).toSeconds();
    }

    private double pctMove(double entry, double price, boolean isLong) {
        double raw = (price - entry) / entry * 100.0;
        return isLong ? raw : -raw;
    }

    private boolean expireIfStale(SignalOutcome outcome, List<CandleBar> bars) {
        Duration age = Duration.between(outcome.getFiredAt(), Instant.now());
        if (age.compareTo(MAX_HOLD) < 0) return false;

        CandleBar last = bars.get(bars.size() - 1);
        closeOutcome(outcome, OutcomeStatus.EXPIRED, last.time(), last.close());
        return true;
    }

    private void closeOutcome(SignalOutcome outcome, OutcomeStatus status,
                              Instant closedAt, double closedPrice) {
        outcome.setStatus(status);
        outcome.setClosedAt(closedAt);
        outcome.setClosedPrice(closedPrice);
        outcome.setLastEvaluatedAt(closedAt);
        outcome.setFinalExitReason(exitReason(outcome, status));

        boolean isLong = DIRECTION_LONG.equals(outcome.getDirection());
        double pnlPct = pctMove(outcome.getEntryPrice(), closedPrice, isLong);
        double risk = Math.abs(outcome.getEntryPrice() - outcome.getStopPrice());
        double rMultipleGross = risk > 0
                ? (closedPrice - outcome.getEntryPrice()) * (isLong ? 1 : -1) / risk
                : 0.0;

        // Convert round-trip fees (in bps) to R-units: fees_pct / risk_pct.
        // A 0.10% fee on a 1% risk eats 0.1R out of every closed trade.
        double feesInR = feesInRUnits(outcome, risk);
        double rMultipleNet = rMultipleGross - feesInR;

        outcome.setRealizedPnlPct(pnlPct);
        outcome.setRealizedRMultiple(rMultipleNet);

        LOG.infof("CLOSE %s %s %s (%s) @ %.4f  pnl=%.2f%%  R=%.2f (gross %.2f, fees %.2f)",
                outcome.getSymbol(), outcome.getDirection(), status,
                outcome.getFinalExitReason(), closedPrice, pnlPct,
                rMultipleNet, rMultipleGross, feesInR);
    }

    /**
     * Cost of round-trip fees expressed in R-units. Zero when risk is zero or
     * fee configuration is missing.
     */
    // Package-private for unit tests — pure function; no side effects.
    double feesInRUnits(SignalOutcome outcome, double risk) {
        if (risk <= 0) return 0.0;
        Integer feesBps = outcome.getFeesBpsRoundTrip();
        if (feesBps == null || feesBps <= 0) return 0.0;
        double riskPct = risk / outcome.getEntryPrice();
        double feesPct = feesBps / 10000.0;
        return feesPct / riskPct;
    }

    /**
     * Resolves the human-readable exit reason. Distinguishes {@code TRAIL_STOP}
     * (dynamic stop filled) from {@code INITIAL_STOP} (never ratcheted) so that
     * post-hoc metrics can attribute profit/loss to the trailing-stop behavior.
     */
    private String exitReason(SignalOutcome outcome, OutcomeStatus status) {
        if (status == OutcomeStatus.HIT_TARGET) return EXIT_TARGET;
        if (status == OutcomeStatus.EXPIRED) return EXIT_EXPIRED;
        return outcome.getDynamicStopPrice() != null ? EXIT_TRAIL_STOP : EXIT_INITIAL_STOP;
    }
}
