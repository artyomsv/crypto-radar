package com.cryptoradar.derivatives.model;

/**
 * An estimated liquidation level at a specific price point.
 * Represents how much USD value would be liquidated if price reaches this level.
 */
public class LiquidationLevel {

    private double price;
    private double cumulativeLongLiqUsd;   // Longs liquidated if price drops to here
    private double cumulativeShortLiqUsd;  // Shorts liquidated if price rises to here
    private int leverage;                   // The leverage tier this level represents

    public LiquidationLevel() {}

    public LiquidationLevel(double price, double cumulativeLongLiqUsd, double cumulativeShortLiqUsd, int leverage) {
        this.price = price;
        this.cumulativeLongLiqUsd = cumulativeLongLiqUsd;
        this.cumulativeShortLiqUsd = cumulativeShortLiqUsd;
        this.leverage = leverage;
    }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    public double getCumulativeLongLiqUsd() { return cumulativeLongLiqUsd; }
    public void setCumulativeLongLiqUsd(double v) { this.cumulativeLongLiqUsd = v; }
    public double getCumulativeShortLiqUsd() { return cumulativeShortLiqUsd; }
    public void setCumulativeShortLiqUsd(double v) { this.cumulativeShortLiqUsd = v; }
    public int getLeverage() { return leverage; }
    public void setLeverage(int leverage) { this.leverage = leverage; }
}
