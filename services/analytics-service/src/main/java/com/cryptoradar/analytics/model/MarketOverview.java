package com.cryptoradar.analytics.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MarketOverview {

    private Instant timestamp;
    private String marketSentiment;

    // Our technical analysis score (0-100, derived from TA indicators)
    private Integer technicalScore;
    private String technicalScoreLabel;

    // Real Fear & Greed Index from alternative.me
    private Integer fearGreedIndex;
    private String fearGreedLabel;

    private Integer bullishCount;
    private Integer bearishCount;
    private Integer neutralCount;
    private String topGainer;
    private String topLoser;
    private List<MarketAnalysis> analyses = new ArrayList<>();

    public MarketOverview() {}

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getMarketSentiment() { return marketSentiment; }
    public void setMarketSentiment(String marketSentiment) { this.marketSentiment = marketSentiment; }

    public Integer getTechnicalScore() { return technicalScore; }
    public void setTechnicalScore(Integer technicalScore) { this.technicalScore = technicalScore; }

    public String getTechnicalScoreLabel() { return technicalScoreLabel; }
    public void setTechnicalScoreLabel(String technicalScoreLabel) { this.technicalScoreLabel = technicalScoreLabel; }

    public Integer getFearGreedIndex() { return fearGreedIndex; }
    public void setFearGreedIndex(Integer fearGreedIndex) { this.fearGreedIndex = fearGreedIndex; }

    public String getFearGreedLabel() { return fearGreedLabel; }
    public void setFearGreedLabel(String fearGreedLabel) { this.fearGreedLabel = fearGreedLabel; }

    public Integer getBullishCount() { return bullishCount; }
    public void setBullishCount(Integer bullishCount) { this.bullishCount = bullishCount; }

    public Integer getBearishCount() { return bearishCount; }
    public void setBearishCount(Integer bearishCount) { this.bearishCount = bearishCount; }

    public Integer getNeutralCount() { return neutralCount; }
    public void setNeutralCount(Integer neutralCount) { this.neutralCount = neutralCount; }

    public String getTopGainer() { return topGainer; }
    public void setTopGainer(String topGainer) { this.topGainer = topGainer; }

    public String getTopLoser() { return topLoser; }
    public void setTopLoser(String topLoser) { this.topLoser = topLoser; }

    public List<MarketAnalysis> getAnalyses() { return analyses; }
    public void setAnalyses(List<MarketAnalysis> analyses) { this.analyses = analyses; }
}
