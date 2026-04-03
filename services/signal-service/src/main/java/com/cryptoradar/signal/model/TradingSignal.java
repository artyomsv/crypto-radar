package com.cryptoradar.signal.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TradingSignal {

    private String symbol;
    private Instant timestamp;
    private String signal;
    private double overallScore;
    private int confidence;
    private List<DimensionScore> dimensions = new ArrayList<>();
    private Double suggestedEntry;
    private Double suggestedStopLoss;
    private Double suggestedTakeProfit;
    private Double riskRewardRatio;
    private String alertLevel;
    private String previousSignal;

    public TradingSignal() {}

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getSignal() { return signal; }
    public void setSignal(String signal) { this.signal = signal; }

    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double overallScore) { this.overallScore = overallScore; }

    public int getConfidence() { return confidence; }
    public void setConfidence(int confidence) { this.confidence = confidence; }

    public List<DimensionScore> getDimensions() { return dimensions; }
    public void setDimensions(List<DimensionScore> dimensions) { this.dimensions = dimensions; }

    public Double getSuggestedEntry() { return suggestedEntry; }
    public void setSuggestedEntry(Double suggestedEntry) { this.suggestedEntry = suggestedEntry; }

    public Double getSuggestedStopLoss() { return suggestedStopLoss; }
    public void setSuggestedStopLoss(Double suggestedStopLoss) { this.suggestedStopLoss = suggestedStopLoss; }

    public Double getSuggestedTakeProfit() { return suggestedTakeProfit; }
    public void setSuggestedTakeProfit(Double suggestedTakeProfit) { this.suggestedTakeProfit = suggestedTakeProfit; }

    public Double getRiskRewardRatio() { return riskRewardRatio; }
    public void setRiskRewardRatio(Double riskRewardRatio) { this.riskRewardRatio = riskRewardRatio; }

    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }

    public String getPreviousSignal() { return previousSignal; }
    public void setPreviousSignal(String previousSignal) { this.previousSignal = previousSignal; }
}
