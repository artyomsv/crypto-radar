package com.cryptoradar.analytics.model;

import java.time.Instant;

public class MacroOverview {

    private double btcDominance;
    private double ethDominance;
    private double totalMarketCapUsd;
    private double usdtMarketCap;
    private double usdcMarketCap;
    private double totalStablecoinCap;
    private double defiTvlUsd;
    private Instant timestamp;

    public MacroOverview() {}

    public double getBtcDominance() { return btcDominance; }
    public void setBtcDominance(double btcDominance) { this.btcDominance = btcDominance; }

    public double getEthDominance() { return ethDominance; }
    public void setEthDominance(double ethDominance) { this.ethDominance = ethDominance; }

    public double getTotalMarketCapUsd() { return totalMarketCapUsd; }
    public void setTotalMarketCapUsd(double totalMarketCapUsd) { this.totalMarketCapUsd = totalMarketCapUsd; }

    public double getUsdtMarketCap() { return usdtMarketCap; }
    public void setUsdtMarketCap(double usdtMarketCap) { this.usdtMarketCap = usdtMarketCap; }

    public double getUsdcMarketCap() { return usdcMarketCap; }
    public void setUsdcMarketCap(double usdcMarketCap) { this.usdcMarketCap = usdcMarketCap; }

    public double getTotalStablecoinCap() { return totalStablecoinCap; }
    public void setTotalStablecoinCap(double totalStablecoinCap) { this.totalStablecoinCap = totalStablecoinCap; }

    public double getDefiTvlUsd() { return defiTvlUsd; }
    public void setDefiTvlUsd(double defiTvlUsd) { this.defiTvlUsd = defiTvlUsd; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
