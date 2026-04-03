package com.cryptoradar.analytics.service;

import com.cryptoradar.analytics.model.MarketAnalysis;
import com.cryptoradar.analytics.model.MarketOverview;
import com.cryptoradar.analytics.model.TechnicalIndicators;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AnalyticsService {

    private static final Logger LOG = Logger.getLogger(AnalyticsService.class);

    private static final List<String> TOP_SYMBOLS = List.of(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "ADAUSDT", "AVAXUSDT", "DOTUSDT", "LINKUSDT", "DOGEUSDT"
    );

    private static final long CACHE_TTL_MS = 30_000;

    private final IndicatorCalculator calculator;
    private final AgroalDataSource dataSource;
    private final NewsServiceClient newsClient;
    private final FearGreedClient fearGreedClient;

    private final ConcurrentHashMap<String, CachedEntry<MarketAnalysis>> analysisCache = new ConcurrentHashMap<>();
    private volatile CachedEntry<MarketOverview> overviewCache;

    public AnalyticsService(IndicatorCalculator calculator,
                            AgroalDataSource dataSource,
                            @RestClient NewsServiceClient newsClient,
                            FearGreedClient fearGreedClient) {
        this.calculator = calculator;
        this.dataSource = dataSource;
        this.newsClient = newsClient;
        this.fearGreedClient = fearGreedClient;
    }

    public MarketAnalysis computeAnalysis(String symbol) {
        List<Object[]> rows = queryCandles(symbol);
        if (rows.isEmpty()) {
            LOG.warnf("No candle data found for %s", symbol);
            return buildEmptyAnalysis(symbol);
        }

        List<Double> closes = new ArrayList<>();
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();

        for (Object[] row : rows) {
            closes.add(toDouble(row[0]));
            highs.add(toDouble(row[1]));
            lows.add(toDouble(row[2]));
            volumes.add(toDouble(row[3]));
        }

        TechnicalIndicators indicators = buildIndicators(symbol, closes, highs, lows);
        double sentiment = fetchSentiment(symbol);
        List<String> signals = generateSignals(closes, indicators);
        double overallScore = calculateOverallScore(indicators, sentiment);
        String volumeTrend = determineVolumeTrend(volumes);

        double[] supportResistance = calculator.calculateSupportResistance(highs, lows, closes);
        Double support = supportResistance != null ? supportResistance[0] : null;
        Double resistance = supportResistance != null ? supportResistance[1] : null;

        MarketAnalysis analysis = new MarketAnalysis();
        analysis.setSymbol(symbol);
        analysis.setTimestamp(Instant.now());
        analysis.setTechnicalIndicators(indicators);
        analysis.setSentimentScore(sentiment);
        analysis.setSignals(signals);
        analysis.setOverallScore(overallScore);
        analysis.setTrendDirection(determineTrendDirection(overallScore));
        analysis.setTrendStrength(Math.abs(overallScore));
        analysis.setVolumeTrend(volumeTrend);
        analysis.setSupportLevel(support);
        analysis.setResistanceLevel(resistance);

        analysisCache.put(symbol, new CachedEntry<>(analysis));
        return analysis;
    }

    public MarketOverview computeMarketOverview() {
        List<MarketAnalysis> analyses = new ArrayList<>();
        for (String symbol : TOP_SYMBOLS) {
            try {
                analyses.add(computeAnalysis(symbol));
            } catch (Exception e) {
                LOG.errorf(e, "Failed to compute analysis for %s", symbol);
            }
        }

        int bullish = 0;
        int bearish = 0;
        int neutral = 0;
        double totalScore = 0.0;

        for (MarketAnalysis analysis : analyses) {
            totalScore += analysis.getOverallScore();
            String trend = analysis.getTrendDirection();
            if ("STRONG_BULLISH".equals(trend) || "BULLISH".equals(trend)) {
                bullish++;
            } else if ("STRONG_BEARISH".equals(trend) || "BEARISH".equals(trend)) {
                bearish++;
            } else {
                neutral++;
            }
        }

        // Technical Score: map average analysis score (-100..+100) to 0..100
        double avgScore = analyses.isEmpty() ? 0.0 : totalScore / analyses.size();
        int technicalScore = (int) Math.round((avgScore + 100.0) / 2.0);
        technicalScore = Math.max(0, Math.min(100, technicalScore));
        String technicalLabel = labelForScore(technicalScore);

        // Real Fear & Greed Index from alternative.me
        int realFearGreed = fearGreedClient.getIndex();
        String realFearGreedLabel = fearGreedClient.getLabel();

        String topGainer = analyses.stream()
                .max(Comparator.comparingDouble(MarketAnalysis::getOverallScore))
                .map(MarketAnalysis::getSymbol)
                .orElse(null);
        String topLoser = analyses.stream()
                .min(Comparator.comparingDouble(MarketAnalysis::getOverallScore))
                .map(MarketAnalysis::getSymbol)
                .orElse(null);

        String marketSentiment = determineTrendDirection(avgScore);

        MarketOverview overview = new MarketOverview();
        overview.setTimestamp(Instant.now());
        overview.setMarketSentiment(marketSentiment);
        overview.setTechnicalScore(technicalScore);
        overview.setTechnicalScoreLabel(technicalLabel);
        overview.setFearGreedIndex(realFearGreed >= 0 ? realFearGreed : null);
        overview.setFearGreedLabel(realFearGreedLabel);
        overview.setBullishCount(bullish);
        overview.setBearishCount(bearish);
        overview.setNeutralCount(neutral);
        overview.setTopGainer(topGainer);
        overview.setTopLoser(topLoser);
        overview.setAnalyses(analyses);

        overviewCache = new CachedEntry<>(overview);
        return overview;
    }

    public MarketAnalysis getAnalysis(String symbol) {
        CachedEntry<MarketAnalysis> cached = analysisCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        return computeAnalysis(symbol);
    }

    public MarketOverview getMarketOverview() {
        CachedEntry<MarketOverview> cached = overviewCache;
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        return computeMarketOverview();
    }

    public List<String> getTopSymbols() {
        return TOP_SYMBOLS;
    }

    private List<Object[]> queryCandles(String symbol) {
        List<Object[]> results = new ArrayList<>();
        // Subquery gets latest 200 candles (DESC), outer query reverses to ASC for indicator math
        String sql = "SELECT close, high, low, volume FROM (" +
                "  SELECT close, high, low, volume, time FROM candles " +
                "  WHERE symbol = ? AND interval = '1h' " +
                "  ORDER BY time DESC LIMIT 200" +
                ") sub ORDER BY time ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new Object[]{
                            rs.getDouble(1), rs.getDouble(2),
                            rs.getDouble(3), rs.getDouble(4)
                    });
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query candles for %s", symbol);
        }
        return results;
    }

    private TechnicalIndicators buildIndicators(String symbol, List<Double> closes,
                                                List<Double> highs, List<Double> lows) {
        TechnicalIndicators ind = new TechnicalIndicators();
        ind.setSymbol(symbol);
        ind.setTimestamp(Instant.now());

        ind.setRsi14(calculator.calculateRSI(closes, 14));
        ind.setEma12(calculator.calculateEMA(closes, 12));
        ind.setEma26(calculator.calculateEMA(closes, 26));
        ind.setSma20(calculator.calculateSMA(closes, 20));
        ind.setSma50(calculator.calculateSMA(closes, 50));
        ind.setSma200(calculator.calculateSMA(closes, 200));
        ind.setAtr14(calculator.calculateATR(highs, lows, closes, 14));

        double[] macd = calculator.calculateMACD(closes);
        if (macd != null) {
            ind.setMacdLine(macd[0]);
            ind.setMacdSignal(Double.isNaN(macd[1]) ? null : macd[1]);
            ind.setMacdHistogram(Double.isNaN(macd[2]) ? null : macd[2]);
        }

        double[] bollinger = calculator.calculateBollingerBands(closes, 20, 2.0);
        if (bollinger != null) {
            ind.setBollingerUpper(bollinger[0]);
            ind.setBollingerMiddle(bollinger[1]);
            ind.setBollingerLower(bollinger[2]);
        }

        return ind;
    }

    private double fetchSentiment(String symbol) {
        try {
            Map<String, Object> response = newsClient.getSentiment(symbol);
            Object avgSentiment = response.get("avgSentiment");
            if (avgSentiment instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Exception e) {
            LOG.debugf("News sentiment unavailable for %s: %s", symbol, e.getMessage());
        }
        return 0.0;
    }

    private List<String> generateSignals(List<Double> closes, TechnicalIndicators ind) {
        List<String> signals = new ArrayList<>();
        double currentClose = closes.get(closes.size() - 1);

        addRsiSignals(ind.getRsi14(), signals);
        addMacdSignals(ind.getMacdHistogram(), signals);
        addSmaSignals(currentClose, ind.getSma200(), signals);
        addBollingerSignals(currentClose, ind, signals);

        return signals;
    }

    private void addRsiSignals(Double rsi, List<String> signals) {
        if (rsi == null) return;
        if (rsi < 30) {
            signals.add("RSI oversold (buy signal)");
        } else if (rsi > 70) {
            signals.add("RSI overbought (sell signal)");
        }
    }

    private void addMacdSignals(Double histogram, List<String> signals) {
        if (histogram == null) return;
        // Positive histogram suggests bullish momentum
        if (histogram > 0) {
            signals.add("MACD bullish crossover");
        } else if (histogram < 0) {
            signals.add("MACD bearish crossover");
        }
    }

    private void addSmaSignals(double close, Double sma200, List<String> signals) {
        if (sma200 == null) return;
        if (close > sma200) {
            signals.add("Price above 200 SMA (bullish)");
        } else {
            signals.add("Price below 200 SMA (bearish)");
        }
    }

    private void addBollingerSignals(double close, TechnicalIndicators ind, List<String> signals) {
        if (ind.getBollingerLower() == null || ind.getBollingerUpper() == null || ind.getAtr14() == null) {
            return;
        }
        double threshold = ind.getAtr14() * 0.5;
        if (close - ind.getBollingerLower() < threshold) {
            signals.add("Near Bollinger lower band (potential bounce)");
        }
        if (ind.getBollingerUpper() - close < threshold) {
            signals.add("Near Bollinger upper band (potential pullback)");
        }
    }

    /**
     * Weighted scoring: RSI 20%, MACD 25%, SMA trend 20%, Bollinger 15%, Sentiment 20%.
     * Each component outputs a value in -100..+100, then weighted.
     */
    private double calculateOverallScore(TechnicalIndicators ind, double sentiment) {
        double rsiScore = calculateRsiScore(ind.getRsi14());
        double macdScore = calculateMacdScore(ind.getMacdHistogram(), ind.getMacdLine());
        double smaScore = calculateSmaScore(ind);
        double bollingerScore = calculateBollingerScore(ind);
        double sentimentScore = sentiment * 100.0; // -1..1 -> -100..100

        double score = rsiScore * 0.20
                + macdScore * 0.25
                + smaScore * 0.20
                + bollingerScore * 0.15
                + sentimentScore * 0.20;

        return Math.max(-100.0, Math.min(100.0, score));
    }

    private double calculateRsiScore(Double rsi) {
        if (rsi == null) return 0.0;
        // RSI 50 = neutral. Below 30 = very bullish (oversold). Above 70 = very bearish (overbought).
        return (50.0 - rsi) * 2.0;
    }

    private double calculateMacdScore(Double histogram, Double macdLine) {
        if (histogram == null || macdLine == null) return 0.0;
        // Normalize histogram relative to signal magnitude
        double magnitude = Math.abs(macdLine) > 0 ? Math.abs(macdLine) : 1.0;
        double normalized = (histogram / magnitude) * 100.0;
        return Math.max(-100.0, Math.min(100.0, normalized));
    }

    private double calculateSmaScore(TechnicalIndicators ind) {
        if (ind.getEma12() == null || ind.getSma50() == null) return 0.0;
        // Price relative to SMAs: EMA12 above SMA50 = bullish
        double diff = ind.getEma12() - ind.getSma50();
        double base = ind.getSma50() > 0 ? ind.getSma50() : 1.0;
        double pctDiff = (diff / base) * 100.0;
        return Math.max(-100.0, Math.min(100.0, pctDiff * 20.0));
    }

    private double calculateBollingerScore(TechnicalIndicators ind) {
        if (ind.getBollingerUpper() == null || ind.getBollingerLower() == null
                || ind.getBollingerMiddle() == null || ind.getEma12() == null) {
            return 0.0;
        }
        // Position within Bollinger Bands: -100 at lower, 0 at middle, +100 at upper
        // Inverted because near lower = buy opportunity (bullish), near upper = sell (bearish)
        double bandWidth = ind.getBollingerUpper() - ind.getBollingerLower();
        if (bandWidth <= 0) return 0.0;

        double position = (ind.getEma12() - ind.getBollingerLower()) / bandWidth;
        // Invert: 0 (at lower) = +100 (bullish), 1 (at upper) = -100 (bearish)
        return (0.5 - position) * 200.0;
    }

    private String labelForScore(int score) {
        if (score >= 75) return "Strong Bullish";
        if (score >= 60) return "Bullish";
        if (score >= 40) return "Neutral";
        if (score >= 25) return "Bearish";
        return "Strong Bearish";
    }

    private String determineTrendDirection(double score) {
        if (score >= 50) return "STRONG_BULLISH";
        if (score >= 20) return "BULLISH";
        if (score <= -50) return "STRONG_BEARISH";
        if (score <= -20) return "BEARISH";
        return "NEUTRAL";
    }

    private String determineVolumeTrend(List<Double> volumes) {
        if (volumes.size() < 20) return "STABLE";

        int recentStart = volumes.size() - 10;
        int priorStart = volumes.size() - 20;

        double recentAvg = 0.0;
        for (int i = recentStart; i < volumes.size(); i++) {
            recentAvg += volumes.get(i);
        }
        recentAvg /= 10;

        double priorAvg = 0.0;
        for (int i = priorStart; i < recentStart; i++) {
            priorAvg += volumes.get(i);
        }
        priorAvg /= 10;

        if (priorAvg <= 0) return "STABLE";

        double ratio = recentAvg / priorAvg;
        if (ratio > 1.2) return "INCREASING";
        if (ratio < 0.8) return "DECREASING";
        return "STABLE";
    }

    private MarketAnalysis buildEmptyAnalysis(String symbol) {
        MarketAnalysis analysis = new MarketAnalysis();
        analysis.setSymbol(symbol);
        analysis.setTimestamp(Instant.now());
        analysis.setOverallScore(0.0);
        analysis.setTrendDirection("NEUTRAL");
        analysis.setTrendStrength(0.0);
        analysis.setSentimentScore(0.0);
        analysis.setVolumeTrend("STABLE");
        analysis.setSignals(List.of("Insufficient data for analysis"));
        return analysis;
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    private record CachedEntry<T>(T value, long createdAt) {
        CachedEntry(T value) {
            this(value, System.currentTimeMillis());
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}
