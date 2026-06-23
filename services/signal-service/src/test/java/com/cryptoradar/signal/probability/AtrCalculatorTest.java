package com.cryptoradar.signal.probability;

import com.cryptoradar.signal.model.CandleBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtrCalculatorTest {

    @Test
    void returnsZeroBelowMinBars() {
        List<CandleBar> bars = constantRangeBars(3, 10, 8); // need period+1
        assertEquals(0.0, AtrCalculator.atr(bars, 14), 1e-9);
    }

    @Test
    void averagesTrueRangeOverPeriod() {
        // Every bar spans high-low = 2, no gaps → ATR = 2.
        List<CandleBar> bars = constantRangeBars(20, 10, 8);
        assertEquals(2.0, AtrCalculator.atr(bars, 14), 1e-9);
    }

    private static List<CandleBar> constantRangeBars(int n, double high, double low) {
        List<CandleBar> bars = new ArrayList<>();
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < n; i++) {
            bars.add(new CandleBar(t, low, high, low, low, 0.0)); // close=low keeps prev-close inside range
            t = t.plusSeconds(3600);
        }
        return bars;
    }
}
