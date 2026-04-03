package com.cryptoradar.derivatives.model;

import java.time.Instant;
import java.util.List;

/**
 * Estimated liquidation map for a symbol — shows where leveraged positions
 * would be liquidated at different price levels.
 */
public class LiquidationMap {

    private String symbol;
    private double currentPrice;
    private double openInterestUsd;
    private double longPct;
    private double shortPct;
    private Instant timestamp;

    // Price levels below current (long liquidation zones)
    private List<LiquidationLevel> longLiquidationLevels;

    // Price levels above current (short liquidation zones)
    private List<LiquidationLevel> shortLiquidationLevels;

    // Key levels
    private double highestLiquidityLongPrice;  // Price with most long liquidations
    private double highestLiquidityShortPrice; // Price with most short liquidations
    private double totalEstimatedLongLiqUsd;
    private double totalEstimatedShortLiqUsd;

    public LiquidationMap() {}

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }
    public double getOpenInterestUsd() { return openInterestUsd; }
    public void setOpenInterestUsd(double openInterestUsd) { this.openInterestUsd = openInterestUsd; }
    public double getLongPct() { return longPct; }
    public void setLongPct(double longPct) { this.longPct = longPct; }
    public double getShortPct() { return shortPct; }
    public void setShortPct(double shortPct) { this.shortPct = shortPct; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public List<LiquidationLevel> getLongLiquidationLevels() { return longLiquidationLevels; }
    public void setLongLiquidationLevels(List<LiquidationLevel> v) { this.longLiquidationLevels = v; }
    public List<LiquidationLevel> getShortLiquidationLevels() { return shortLiquidationLevels; }
    public void setShortLiquidationLevels(List<LiquidationLevel> v) { this.shortLiquidationLevels = v; }
    public double getHighestLiquidityLongPrice() { return highestLiquidityLongPrice; }
    public void setHighestLiquidityLongPrice(double v) { this.highestLiquidityLongPrice = v; }
    public double getHighestLiquidityShortPrice() { return highestLiquidityShortPrice; }
    public void setHighestLiquidityShortPrice(double v) { this.highestLiquidityShortPrice = v; }
    public double getTotalEstimatedLongLiqUsd() { return totalEstimatedLongLiqUsd; }
    public void setTotalEstimatedLongLiqUsd(double v) { this.totalEstimatedLongLiqUsd = v; }
    public double getTotalEstimatedShortLiqUsd() { return totalEstimatedShortLiqUsd; }
    public void setTotalEstimatedShortLiqUsd(double v) { this.totalEstimatedShortLiqUsd = v; }
}
