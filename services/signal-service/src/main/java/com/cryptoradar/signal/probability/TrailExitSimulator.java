package com.cryptoradar.signal.probability;

import com.cryptoradar.core.TrailCalculator;
import com.cryptoradar.core.TrailConfig;
import com.cryptoradar.signal.model.CandleBar;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pure forward simulation of a trailing-stop exit over a candidate's realized 1h
 * path, in R-units, reusing the production trail ladder ({@link TrailCalculator}).
 * Shadow candidates tagged for trailing are scored with this instead of a fixed
 * 1:1 target so their measured EV reflects letting winners run — a mid-experiment
 * backtest of v3 showed the direction model picks winners that run ~2.7 ATR while
 * losers fail at ~0.45 ATR, so trailing roughly triples EV at the same win rate.
 *
 * <p>Convention: within a bar the active stop is checked against the trail level
 * established by PRIOR bars before this bar's favorable move advances the ladder
 * — conservative, matching {@code ShadowOutcomeEvaluator}'s stop-first rule. The
 * initial stop sits at -1R until the trail first advances.
 */
final class TrailExitSimulator {

    private TrailExitSimulator() {}

    /**
     * @param resolved  true if a stop (initial or trailing) fired within the path
     * @param status    HIT_TARGET (exit in profit) / HIT_STOP (exit at a loss), only when resolved
     * @param exitPrice exit price, only when resolved
     * @param exitTime  bar time of the exit, only when resolved
     * @param mfeAtr    max favorable excursion over the walked path (ATR units)
     * @param maeAtr    max adverse excursion over the walked path (ATR units)
     */
    record Result(boolean resolved, String status, double exitPrice, Instant exitTime,
                  double mfeAtr, double maeAtr) {}

    static Result simulate(boolean isLong, double entry, double risk, double atr,
                           TrailConfig cfg, List<CandleBar> bars, Instant scannedAt) {
        double mfeAtr = 0.0;
        double maeAtr = 0.0;
        double mfeR = 0.0;
        double highestTrailR = 0.0;   // 0 ⇒ trail not yet advanced; initial stop active at -1R
        boolean trailActive = false;

        for (CandleBar bar : bars) {
            if (!bar.time().isAfter(scannedAt)) continue;

            double favBest = (isLong ? bar.high() - entry : entry - bar.low()) / risk;
            double favWorst = (isLong ? bar.low() - entry : entry - bar.high()) / risk;
            mfeAtr = Math.max(mfeAtr, (isLong ? bar.high() - entry : entry - bar.low()) / atr);
            maeAtr = Math.max(maeAtr, (isLong ? entry - bar.low() : bar.high() - entry) / atr);

            double stopR = trailActive ? highestTrailR : -1.0;
            if (favWorst <= stopR) {
                double exitPrice = isLong ? entry + stopR * risk : entry - stopR * risk;
                String status = stopR > 0
                        ? ProbabilityCandidate.STATUS_HIT_TARGET
                        : ProbabilityCandidate.STATUS_HIT_STOP;
                return new Result(true, status, exitPrice, bar.time(), mfeAtr, maeAtr);
            }

            mfeR = Math.max(mfeR, favBest);
            Optional<Double> newTrail = TrailCalculator.computeNewTrailR(mfeR, cfg, highestTrailR);
            if (newTrail.isPresent()) {
                highestTrailR = newTrail.get();
                trailActive = true;
            }
        }
        return new Result(false, null, 0.0, null, mfeAtr, maeAtr);
    }
}
