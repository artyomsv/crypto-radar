package com.cryptoradar.whale.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WhaleMarketOverview {

    private Instant timestamp;
    private double totalWhaleVolume24h;
    private double totalBuyVolume24h;
    private double totalSellVolume24h;
    private double overallPressure;
    private String overallPressureLabel;
    private String mostBought;
    private String mostSold;
    private int activeSymbolCount;
    private int totalTradeCount24h;
    private List<WhaleAnalytics> symbolAnalytics = new ArrayList<>();

    public WhaleMarketOverview() {
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public double getTotalWhaleVolume24h() {
        return totalWhaleVolume24h;
    }

    public void setTotalWhaleVolume24h(double totalWhaleVolume24h) {
        this.totalWhaleVolume24h = totalWhaleVolume24h;
    }

    public double getTotalBuyVolume24h() {
        return totalBuyVolume24h;
    }

    public void setTotalBuyVolume24h(double totalBuyVolume24h) {
        this.totalBuyVolume24h = totalBuyVolume24h;
    }

    public double getTotalSellVolume24h() {
        return totalSellVolume24h;
    }

    public void setTotalSellVolume24h(double totalSellVolume24h) {
        this.totalSellVolume24h = totalSellVolume24h;
    }

    public double getOverallPressure() {
        return overallPressure;
    }

    public void setOverallPressure(double overallPressure) {
        this.overallPressure = overallPressure;
    }

    public String getOverallPressureLabel() {
        return overallPressureLabel;
    }

    public void setOverallPressureLabel(String overallPressureLabel) {
        this.overallPressureLabel = overallPressureLabel;
    }

    public String getMostBought() {
        return mostBought;
    }

    public void setMostBought(String mostBought) {
        this.mostBought = mostBought;
    }

    public String getMostSold() {
        return mostSold;
    }

    public void setMostSold(String mostSold) {
        this.mostSold = mostSold;
    }

    public int getActiveSymbolCount() {
        return activeSymbolCount;
    }

    public void setActiveSymbolCount(int activeSymbolCount) {
        this.activeSymbolCount = activeSymbolCount;
    }

    public int getTotalTradeCount24h() {
        return totalTradeCount24h;
    }

    public void setTotalTradeCount24h(int totalTradeCount24h) {
        this.totalTradeCount24h = totalTradeCount24h;
    }

    public List<WhaleAnalytics> getSymbolAnalytics() {
        return symbolAnalytics;
    }

    public void setSymbolAnalytics(List<WhaleAnalytics> symbolAnalytics) {
        this.symbolAnalytics = symbolAnalytics;
    }
}
