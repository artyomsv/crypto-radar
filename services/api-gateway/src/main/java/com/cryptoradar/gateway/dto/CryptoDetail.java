package com.cryptoradar.gateway.dto;

import java.util.List;

public class CryptoDetail {

    private PriceData priceData;
    private List<Object> candles;
    private Object technicalIndicators;
    private Object analysis;
    private List<Object> news;
    private List<Object> sentimentHistory;

    public CryptoDetail() {
    }

    public PriceData getPriceData() {
        return priceData;
    }

    public void setPriceData(PriceData priceData) {
        this.priceData = priceData;
    }

    public List<Object> getCandles() {
        return candles;
    }

    public void setCandles(List<Object> candles) {
        this.candles = candles;
    }

    public Object getTechnicalIndicators() {
        return technicalIndicators;
    }

    public void setTechnicalIndicators(Object technicalIndicators) {
        this.technicalIndicators = technicalIndicators;
    }

    public Object getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Object analysis) {
        this.analysis = analysis;
    }

    public List<Object> getNews() {
        return news;
    }

    public void setNews(List<Object> news) {
        this.news = news;
    }

    public List<Object> getSentimentHistory() {
        return sentimentHistory;
    }

    public void setSentimentHistory(List<Object> sentimentHistory) {
        this.sentimentHistory = sentimentHistory;
    }
}
