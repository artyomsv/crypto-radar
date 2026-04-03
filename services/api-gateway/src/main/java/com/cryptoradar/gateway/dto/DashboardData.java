package com.cryptoradar.gateway.dto;

import java.time.Instant;
import java.util.List;

public class DashboardData {

    private List<PriceData> prices;
    private Object marketOverview;
    private List<Object> latestNews;
    private Instant timestamp;

    public DashboardData() {
    }

    public List<PriceData> getPrices() {
        return prices;
    }

    public void setPrices(List<PriceData> prices) {
        this.prices = prices;
    }

    public Object getMarketOverview() {
        return marketOverview;
    }

    public void setMarketOverview(Object marketOverview) {
        this.marketOverview = marketOverview;
    }

    public List<Object> getLatestNews() {
        return latestNews;
    }

    public void setLatestNews(List<Object> latestNews) {
        this.latestNews = latestNews;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
