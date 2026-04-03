package com.cryptoradar.news.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "daily_sentiment")
@IdClass(DailySentimentId.class)
public class DailySentiment {

    @Id
    @Column(nullable = false)
    private LocalDate date;

    @Id
    @Column(nullable = false, length = 10)
    private String symbol;

    @Column(name = "avg_sentiment")
    private Double avgSentiment;

    @Column(name = "positive_count")
    private Integer positiveCount;

    @Column(name = "negative_count")
    private Integer negativeCount;

    @Column(name = "neutral_count")
    private Integer neutralCount;

    @Column(name = "total_articles")
    private Integer totalArticles;

    public DailySentiment() {
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Double getAvgSentiment() {
        return avgSentiment;
    }

    public void setAvgSentiment(Double avgSentiment) {
        this.avgSentiment = avgSentiment;
    }

    public Integer getPositiveCount() {
        return positiveCount;
    }

    public void setPositiveCount(Integer positiveCount) {
        this.positiveCount = positiveCount;
    }

    public Integer getNegativeCount() {
        return negativeCount;
    }

    public void setNegativeCount(Integer negativeCount) {
        this.negativeCount = negativeCount;
    }

    public Integer getNeutralCount() {
        return neutralCount;
    }

    public void setNeutralCount(Integer neutralCount) {
        this.neutralCount = neutralCount;
    }

    public Integer getTotalArticles() {
        return totalArticles;
    }

    public void setTotalArticles(Integer totalArticles) {
        this.totalArticles = totalArticles;
    }
}
