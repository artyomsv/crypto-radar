package com.cryptoradar.whale.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class WhaleFlowSummaryTest {

    @Test
    void constructor_setsAllFields() {
        Instant now = Instant.now();
        WhaleFlowSummary summary = new WhaleFlowSummary(
                now, "BTCUSDT", 5, 3, 500000, 300000, 200000, 100000, 250000, 25.0);

        assertEquals(now, summary.getBucket());
        assertEquals("BTCUSDT", summary.getSymbol());
        assertEquals(5, summary.getBuyCount());
        assertEquals(3, summary.getSellCount());
        assertEquals(500000, summary.getBuyVolumeUsd(), 0.01);
        assertEquals(300000, summary.getSellVolumeUsd(), 0.01);
        assertEquals(200000, summary.getNetFlowUsd(), 0.01);
        assertEquals(100000, summary.getAvgTradeSizeUsd(), 0.01);
        assertEquals(250000, summary.getLargestTradeUsd(), 0.01);
        assertEquals(25.0, summary.getWhalePressure(), 0.01);
    }

    @Test
    void netFlow_positiveMeansBuyingPressure() {
        WhaleFlowSummary summary = new WhaleFlowSummary(
                Instant.now(), "ETHUSDT", 10, 2, 1000000, 200000, 800000, 100000, 500000, 66.67);

        assertTrue(summary.getNetFlowUsd() > 0);
        assertTrue(summary.getWhalePressure() > 0);
    }

    @Test
    void netFlow_negativeMeansSellingPressure() {
        WhaleFlowSummary summary = new WhaleFlowSummary(
                Instant.now(), "BTCUSDT", 1, 8, 100000, 800000, -700000, 100000, 400000, -77.78);

        assertTrue(summary.getNetFlowUsd() < 0);
        assertTrue(summary.getWhalePressure() < 0);
    }
}
