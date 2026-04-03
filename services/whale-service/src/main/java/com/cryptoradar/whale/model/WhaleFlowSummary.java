package com.cryptoradar.whale.model;

import java.time.Instant;

public class WhaleFlowSummary {

    private Instant bucket;
    private String symbol;
    private int buyCount;
    private int sellCount;
    private double buyVolumeUsd;
    private double sellVolumeUsd;
    private double netFlowUsd;
    private double avgTradeSizeUsd;
    private double largestTradeUsd;
    private double whalePressure;

    public WhaleFlowSummary() {
    }

    public WhaleFlowSummary(Instant bucket, String symbol, int buyCount, int sellCount,
                            double buyVolumeUsd, double sellVolumeUsd, double netFlowUsd,
                            double avgTradeSizeUsd, double largestTradeUsd, double whalePressure) {
        this.bucket = bucket;
        this.symbol = symbol;
        this.buyCount = buyCount;
        this.sellCount = sellCount;
        this.buyVolumeUsd = buyVolumeUsd;
        this.sellVolumeUsd = sellVolumeUsd;
        this.netFlowUsd = netFlowUsd;
        this.avgTradeSizeUsd = avgTradeSizeUsd;
        this.largestTradeUsd = largestTradeUsd;
        this.whalePressure = whalePressure;
    }

    public Instant getBucket() {
        return bucket;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getBuyCount() {
        return buyCount;
    }

    public int getSellCount() {
        return sellCount;
    }

    public double getBuyVolumeUsd() {
        return buyVolumeUsd;
    }

    public double getSellVolumeUsd() {
        return sellVolumeUsd;
    }

    public double getNetFlowUsd() {
        return netFlowUsd;
    }

    public double getAvgTradeSizeUsd() {
        return avgTradeSizeUsd;
    }

    public double getLargestTradeUsd() {
        return largestTradeUsd;
    }

    public double getWhalePressure() {
        return whalePressure;
    }
}
