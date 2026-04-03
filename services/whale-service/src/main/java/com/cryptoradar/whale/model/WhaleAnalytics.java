package com.cryptoradar.whale.model;

import java.time.Instant;

public class WhaleAnalytics {

    private String symbol;
    private Instant timestamp;
    private int whaleActivityScore;
    private double whalePressure;
    private String pressureLabel;
    private double buyVolumeUsd1h;
    private double sellVolumeUsd1h;
    private double netFlowUsd1h;
    private int tradeCount1h;
    private double largestTradeUsd;
    private double avgTradeSizeUsd;
    private double buyVolumeUsd24h;
    private double sellVolumeUsd24h;
    private double netFlowUsd24h;
    private int tradeCount24h;

    public WhaleAnalytics() {
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public int getWhaleActivityScore() {
        return whaleActivityScore;
    }

    public void setWhaleActivityScore(int whaleActivityScore) {
        this.whaleActivityScore = whaleActivityScore;
    }

    public double getWhalePressure() {
        return whalePressure;
    }

    public void setWhalePressure(double whalePressure) {
        this.whalePressure = whalePressure;
    }

    public String getPressureLabel() {
        return pressureLabel;
    }

    public void setPressureLabel(String pressureLabel) {
        this.pressureLabel = pressureLabel;
    }

    public double getBuyVolumeUsd1h() {
        return buyVolumeUsd1h;
    }

    public void setBuyVolumeUsd1h(double buyVolumeUsd1h) {
        this.buyVolumeUsd1h = buyVolumeUsd1h;
    }

    public double getSellVolumeUsd1h() {
        return sellVolumeUsd1h;
    }

    public void setSellVolumeUsd1h(double sellVolumeUsd1h) {
        this.sellVolumeUsd1h = sellVolumeUsd1h;
    }

    public double getNetFlowUsd1h() {
        return netFlowUsd1h;
    }

    public void setNetFlowUsd1h(double netFlowUsd1h) {
        this.netFlowUsd1h = netFlowUsd1h;
    }

    public int getTradeCount1h() {
        return tradeCount1h;
    }

    public void setTradeCount1h(int tradeCount1h) {
        this.tradeCount1h = tradeCount1h;
    }

    public double getLargestTradeUsd() {
        return largestTradeUsd;
    }

    public void setLargestTradeUsd(double largestTradeUsd) {
        this.largestTradeUsd = largestTradeUsd;
    }

    public double getAvgTradeSizeUsd() {
        return avgTradeSizeUsd;
    }

    public void setAvgTradeSizeUsd(double avgTradeSizeUsd) {
        this.avgTradeSizeUsd = avgTradeSizeUsd;
    }

    public double getBuyVolumeUsd24h() {
        return buyVolumeUsd24h;
    }

    public void setBuyVolumeUsd24h(double buyVolumeUsd24h) {
        this.buyVolumeUsd24h = buyVolumeUsd24h;
    }

    public double getSellVolumeUsd24h() {
        return sellVolumeUsd24h;
    }

    public void setSellVolumeUsd24h(double sellVolumeUsd24h) {
        this.sellVolumeUsd24h = sellVolumeUsd24h;
    }

    public double getNetFlowUsd24h() {
        return netFlowUsd24h;
    }

    public void setNetFlowUsd24h(double netFlowUsd24h) {
        this.netFlowUsd24h = netFlowUsd24h;
    }

    public int getTradeCount24h() {
        return tradeCount24h;
    }

    public void setTradeCount24h(int tradeCount24h) {
        this.tradeCount24h = tradeCount24h;
    }
}
