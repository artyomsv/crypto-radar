package com.cryptoradar.marketdata.model;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated order book depth from multiple exchanges.
 * Bids sorted by price DESC, asks sorted by price ASC.
 */
public class OrderBookDepth {

    private String symbol;
    private Instant timestamp;
    private List<PriceLevel> bids;
    private List<PriceLevel> asks;
    private double bestBid;
    private double bestAsk;
    private double spread;
    private double spreadPct;
    private double totalBidVolume;
    private double totalAskVolume;
    private double bidAskImbalance;

    public OrderBookDepth() {
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

    public List<PriceLevel> getBids() {
        return bids;
    }

    public void setBids(List<PriceLevel> bids) {
        this.bids = bids;
    }

    public List<PriceLevel> getAsks() {
        return asks;
    }

    public void setAsks(List<PriceLevel> asks) {
        this.asks = asks;
    }

    public double getBestBid() {
        return bestBid;
    }

    public void setBestBid(double bestBid) {
        this.bestBid = bestBid;
    }

    public double getBestAsk() {
        return bestAsk;
    }

    public void setBestAsk(double bestAsk) {
        this.bestAsk = bestAsk;
    }

    public double getSpread() {
        return spread;
    }

    public void setSpread(double spread) {
        this.spread = spread;
    }

    public double getSpreadPct() {
        return spreadPct;
    }

    public void setSpreadPct(double spreadPct) {
        this.spreadPct = spreadPct;
    }

    public double getTotalBidVolume() {
        return totalBidVolume;
    }

    public void setTotalBidVolume(double totalBidVolume) {
        this.totalBidVolume = totalBidVolume;
    }

    public double getTotalAskVolume() {
        return totalAskVolume;
    }

    public void setTotalAskVolume(double totalAskVolume) {
        this.totalAskVolume = totalAskVolume;
    }

    public double getBidAskImbalance() {
        return bidAskImbalance;
    }

    public void setBidAskImbalance(double bidAskImbalance) {
        this.bidAskImbalance = bidAskImbalance;
    }
}
