package com.cryptoradar.derivatives.model;

import java.time.Instant;
import java.util.List;

public class DerivativesOverview {

    private Instant timestamp;
    private double totalOpenInterestUsd;
    private double avgFundingRate;
    private String fundingRateLabel;
    private double totalLiquidations24h;
    private double longLiquidations24h;
    private double shortLiquidations24h;
    private double marketLongPct;
    private double marketShortPct;
    private List<SymbolDerivatives> symbolData;

    public DerivativesOverview() {}

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public double getTotalOpenInterestUsd() { return totalOpenInterestUsd; }
    public void setTotalOpenInterestUsd(double totalOpenInterestUsd) { this.totalOpenInterestUsd = totalOpenInterestUsd; }

    public double getAvgFundingRate() { return avgFundingRate; }
    public void setAvgFundingRate(double avgFundingRate) { this.avgFundingRate = avgFundingRate; }

    public String getFundingRateLabel() { return fundingRateLabel; }
    public void setFundingRateLabel(String fundingRateLabel) { this.fundingRateLabel = fundingRateLabel; }

    public double getTotalLiquidations24h() { return totalLiquidations24h; }
    public void setTotalLiquidations24h(double totalLiquidations24h) { this.totalLiquidations24h = totalLiquidations24h; }

    public double getLongLiquidations24h() { return longLiquidations24h; }
    public void setLongLiquidations24h(double longLiquidations24h) { this.longLiquidations24h = longLiquidations24h; }

    public double getShortLiquidations24h() { return shortLiquidations24h; }
    public void setShortLiquidations24h(double shortLiquidations24h) { this.shortLiquidations24h = shortLiquidations24h; }

    public double getMarketLongPct() { return marketLongPct; }
    public void setMarketLongPct(double marketLongPct) { this.marketLongPct = marketLongPct; }

    public double getMarketShortPct() { return marketShortPct; }
    public void setMarketShortPct(double marketShortPct) { this.marketShortPct = marketShortPct; }

    public List<SymbolDerivatives> getSymbolData() { return symbolData; }
    public void setSymbolData(List<SymbolDerivatives> symbolData) { this.symbolData = symbolData; }
}
