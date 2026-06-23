package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;

import java.util.List;

/**
 * Walks already-sliced forward bars and returns the realized status of a 1:1
 * shadow trade — the training label for {@link DirectionModel}. Encodes the same
 * stop-first-on-straddle rule as {@link ShadowOutcomeEvaluator} so model labels
 * and live shadow outcomes mean the same thing. Pure — caller supplies the
 * forward slice (bars strictly after entry), so there is no look-ahead here.
 */
public final class LabelWalker {

    private LabelWalker() {}

    public static String resolve(List<CandleBar> forwardBars, double entry,
                                 double stop, double target, boolean isLong) {
        for (CandleBar bar : forwardBars) {
            if (isLong) {
                if (bar.low() <= stop) return ProbabilityCandidate.STATUS_HIT_STOP;
                if (bar.high() >= target) return ProbabilityCandidate.STATUS_HIT_TARGET;
            } else {
                if (bar.high() >= stop) return ProbabilityCandidate.STATUS_HIT_STOP;
                if (bar.low() <= target) return ProbabilityCandidate.STATUS_HIT_TARGET;
            }
        }
        return ProbabilityCandidate.STATUS_EXPIRED;
    }
}
