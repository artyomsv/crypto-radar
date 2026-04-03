package com.cryptoradar.analytics.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MarketAnalysis {

    private String symbol;
    private Instant timestamp;
    private Double overallScore;
    private String trendDirection;
    private Double trendStrength;
    private TechnicalIndicators technicalIndicators;
    private Double sentimentScore;
    private String volumeTrend;
    private Double supportLevel;
    private Double resistanceLevel;
    private List<String> signals = new ArrayList<>();

    public MarketAnalysis() {
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

    public Double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Double overallScore) {
        this.overallScore = overallScore;
    }

    public String getTrendDirection() {
        return trendDirection;
    }

    public void setTrendDirection(String trendDirection) {
        this.trendDirection = trendDirection;
    }

    public Double getTrendStrength() {
        return trendStrength;
    }

    public void setTrendStrength(Double trendStrength) {
        this.trendStrength = trendStrength;
    }

    public TechnicalIndicators getTechnicalIndicators() {
        return technicalIndicators;
    }

    public void setTechnicalIndicators(TechnicalIndicators technicalIndicators) {
        this.technicalIndicators = technicalIndicators;
    }

    public Double getSentimentScore() {
        return sentimentScore;
    }

    public void setSentimentScore(Double sentimentScore) {
        this.sentimentScore = sentimentScore;
    }

    public String getVolumeTrend() {
        return volumeTrend;
    }

    public void setVolumeTrend(String volumeTrend) {
        this.volumeTrend = volumeTrend;
    }

    public Double getSupportLevel() {
        return supportLevel;
    }

    public void setSupportLevel(Double supportLevel) {
        this.supportLevel = supportLevel;
    }

    public Double getResistanceLevel() {
        return resistanceLevel;
    }

    public void setResistanceLevel(Double resistanceLevel) {
        this.resistanceLevel = resistanceLevel;
    }

    public List<String> getSignals() {
        return signals;
    }

    public void setSignals(List<String> signals) {
        this.signals = signals;
    }
}
