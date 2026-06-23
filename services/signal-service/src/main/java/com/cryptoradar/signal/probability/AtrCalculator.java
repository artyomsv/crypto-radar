package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;

import java.util.List;

/**
 * Simple-average ATR over the last {@code period} true ranges. Pure helper shared
 * by the hourly scanner and the direction-model trainer so both derive geometry
 * from identical volatility math. Returns 0 when there are too few bars.
 */
public final class AtrCalculator {

    private AtrCalculator() {}

    public static double atr(List<CandleBar> bars, int period) {
        if (bars.size() < period + 1) return 0.0;
        double sum = 0;
        int count = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            CandleBar cur = bars.get(i);
            CandleBar prev = bars.get(i - 1);
            double tr = Math.max(cur.high() - cur.low(),
                    Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
