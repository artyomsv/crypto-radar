package com.cryptoradar.signal.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SignalOverview {

    private Instant timestamp;
    private int strongBuyCount;
    private int buyCount;
    private int neutralCount;
    private int sellCount;
    private int strongSellCount;
    private String marketBias;
    private String marketRegime;
    private TradingSignal topOpportunity;
    private List<TradingSignal> signals = new ArrayList<>();

    public SignalOverview() {}

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public int getStrongBuyCount() { return strongBuyCount; }
    public void setStrongBuyCount(int strongBuyCount) { this.strongBuyCount = strongBuyCount; }

    public int getBuyCount() { return buyCount; }
    public void setBuyCount(int buyCount) { this.buyCount = buyCount; }

    public int getNeutralCount() { return neutralCount; }
    public void setNeutralCount(int neutralCount) { this.neutralCount = neutralCount; }

    public int getSellCount() { return sellCount; }
    public void setSellCount(int sellCount) { this.sellCount = sellCount; }

    public int getStrongSellCount() { return strongSellCount; }
    public void setStrongSellCount(int strongSellCount) { this.strongSellCount = strongSellCount; }

    public String getMarketBias() { return marketBias; }
    public void setMarketBias(String marketBias) { this.marketBias = marketBias; }

    public String getMarketRegime() { return marketRegime; }
    public void setMarketRegime(String marketRegime) { this.marketRegime = marketRegime; }

    public TradingSignal getTopOpportunity() { return topOpportunity; }
    public void setTopOpportunity(TradingSignal topOpportunity) { this.topOpportunity = topOpportunity; }

    public List<TradingSignal> getSignals() { return signals; }
    public void setSignals(List<TradingSignal> signals) { this.signals = signals; }
}
