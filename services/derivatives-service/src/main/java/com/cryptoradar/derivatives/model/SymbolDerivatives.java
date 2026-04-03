package com.cryptoradar.derivatives.model;

public class SymbolDerivatives {

    private String symbol;
    private double fundingRate;
    private double fundingRateAnnualized;
    private double openInterestUsd;
    private double longShortRatio;
    private double longPct;
    private double shortPct;
    private double liquidations24hUsd;
    private String sentiment;

    public SymbolDerivatives() {}

    public SymbolDerivatives(String symbol, double fundingRate, double fundingRateAnnualized,
                             double openInterestUsd, double longShortRatio, double longPct,
                             double shortPct, double liquidations24hUsd, String sentiment) {
        this.symbol = symbol;
        this.fundingRate = fundingRate;
        this.fundingRateAnnualized = fundingRateAnnualized;
        this.openInterestUsd = openInterestUsd;
        this.longShortRatio = longShortRatio;
        this.longPct = longPct;
        this.shortPct = shortPct;
        this.liquidations24hUsd = liquidations24hUsd;
        this.sentiment = sentiment;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public double getFundingRate() { return fundingRate; }
    public void setFundingRate(double fundingRate) { this.fundingRate = fundingRate; }

    public double getFundingRateAnnualized() { return fundingRateAnnualized; }
    public void setFundingRateAnnualized(double fundingRateAnnualized) { this.fundingRateAnnualized = fundingRateAnnualized; }

    public double getOpenInterestUsd() { return openInterestUsd; }
    public void setOpenInterestUsd(double openInterestUsd) { this.openInterestUsd = openInterestUsd; }

    public double getLongShortRatio() { return longShortRatio; }
    public void setLongShortRatio(double longShortRatio) { this.longShortRatio = longShortRatio; }

    public double getLongPct() { return longPct; }
    public void setLongPct(double longPct) { this.longPct = longPct; }

    public double getShortPct() { return shortPct; }
    public void setShortPct(double shortPct) { this.shortPct = shortPct; }

    public double getLiquidations24hUsd() { return liquidations24hUsd; }
    public void setLiquidations24hUsd(double liquidations24hUsd) { this.liquidations24hUsd = liquidations24hUsd; }

    public String getSentiment() { return sentiment; }
    public void setSentiment(String sentiment) { this.sentiment = sentiment; }
}
