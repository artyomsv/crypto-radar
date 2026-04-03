package com.cryptoradar.marketdata.model;

/**
 * A single price level in the aggregated order book.
 * Represents the sum of quantities at this price across multiple exchanges.
 */
public class PriceLevel {

    private double price;
    private double quantity;
    private double totalUsd;
    private int exchangeCount;

    public PriceLevel() {
    }

    public PriceLevel(double price, double quantity, double totalUsd, int exchangeCount) {
        this.price = price;
        this.quantity = quantity;
        this.totalUsd = totalUsd;
        this.exchangeCount = exchangeCount;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public double getTotalUsd() {
        return totalUsd;
    }

    public void setTotalUsd(double totalUsd) {
        this.totalUsd = totalUsd;
    }

    public int getExchangeCount() {
        return exchangeCount;
    }

    public void setExchangeCount(int exchangeCount) {
        this.exchangeCount = exchangeCount;
    }
}
