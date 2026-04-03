package com.cryptoradar.derivatives.model;

import java.time.Instant;

public class FundingRate {

    private String symbol;
    private double fundingRate;
    private Instant fundingTime;
    private Instant nextFundingTime;
    private double markPrice;
    private double indexPrice;

    public FundingRate() {}

    public FundingRate(String symbol, double fundingRate, Instant fundingTime,
                       Instant nextFundingTime, double markPrice, double indexPrice) {
        this.symbol = symbol;
        this.fundingRate = fundingRate;
        this.fundingTime = fundingTime;
        this.nextFundingTime = nextFundingTime;
        this.markPrice = markPrice;
        this.indexPrice = indexPrice;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public double getFundingRate() { return fundingRate; }
    public void setFundingRate(double fundingRate) { this.fundingRate = fundingRate; }

    public Instant getFundingTime() { return fundingTime; }
    public void setFundingTime(Instant fundingTime) { this.fundingTime = fundingTime; }

    public Instant getNextFundingTime() { return nextFundingTime; }
    public void setNextFundingTime(Instant nextFundingTime) { this.nextFundingTime = nextFundingTime; }

    public double getMarkPrice() { return markPrice; }
    public void setMarkPrice(double markPrice) { this.markPrice = markPrice; }

    public double getIndexPrice() { return indexPrice; }
    public void setIndexPrice(double indexPrice) { this.indexPrice = indexPrice; }
}
