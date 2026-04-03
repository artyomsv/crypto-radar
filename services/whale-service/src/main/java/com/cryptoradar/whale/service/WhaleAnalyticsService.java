package com.cryptoradar.whale.service;

import com.cryptoradar.whale.model.WhaleAnalytics;
import com.cryptoradar.whale.model.WhaleMarketOverview;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class WhaleAnalyticsService {

    private static final Logger LOG = Logger.getLogger(WhaleAnalyticsService.class);

    private static final List<String> TOP_SYMBOLS = List.of(
            "BTCUSDT", "ETHUSDT", "XRPUSDT", "BNBUSDT", "SOLUSDT",
            "TRXUSDT", "DOGEUSDT", "BCHUSDT", "ADAUSDT", "LINKUSDT",
            "XMRUSDT", "XLMUSDT", "LTCUSDT", "ZECUSDT"
    );

    private static final long CACHE_TTL_MS = 30_000;

    // Average expected trades per hour for activity score normalization
    private static final int BASELINE_TRADES_PER_HOUR = 20;

    private static final String STATS_SQL = """
            SELECT
                COUNT(*) AS trade_count,
                COALESCE(SUM(value_usd) FILTER (WHERE side = 'BUY'), 0) AS buy_volume,
                COALESCE(SUM(value_usd) FILTER (WHERE side = 'SELL'), 0) AS sell_volume,
                COALESCE(MAX(value_usd), 0) AS largest_trade,
                COALESCE(AVG(value_usd), 0) AS avg_trade_size
            FROM whale_transactions
            WHERE symbol = ? AND time > NOW() - ?::interval
            """;

    private final AgroalDataSource dataSource;
    private final ConcurrentHashMap<String, CachedEntry<WhaleAnalytics>> analyticsCache = new ConcurrentHashMap<>();
    private volatile CachedEntry<WhaleMarketOverview> overviewCache;

    public WhaleAnalyticsService(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public WhaleAnalytics computeAnalytics(String symbol) {
        WhaleAnalytics analytics = new WhaleAnalytics();
        analytics.setSymbol(symbol);
        analytics.setTimestamp(Instant.now());

        populateWindowStats(analytics, symbol, "1 hour", true);
        populateWindowStats(analytics, symbol, "24 hours", false);
        computeDerivedMetrics(analytics);

        analyticsCache.put(symbol, new CachedEntry<>(analytics));
        return analytics;
    }

    public WhaleAnalytics getAnalytics(String symbol) {
        CachedEntry<WhaleAnalytics> cached = analyticsCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        return computeAnalytics(symbol);
    }

    public WhaleMarketOverview computeMarketOverview() {
        List<WhaleAnalytics> allAnalytics = new ArrayList<>();
        double totalBuyVolume = 0.0;
        double totalSellVolume = 0.0;
        int totalTradeCount = 0;
        int activeCount = 0;

        for (String symbol : TOP_SYMBOLS) {
            try {
                WhaleAnalytics analytics = computeAnalytics(symbol);
                allAnalytics.add(analytics);
                totalBuyVolume += analytics.getBuyVolumeUsd24h();
                totalSellVolume += analytics.getSellVolumeUsd24h();
                totalTradeCount += analytics.getTradeCount24h();
                if (analytics.getTradeCount24h() > 0) {
                    activeCount++;
                }
            } catch (Exception e) {
                LOG.errorf(e, "Failed to compute whale analytics for %s", symbol);
            }
        }

        double totalVolume = totalBuyVolume + totalSellVolume;
        double netFlow = totalBuyVolume - totalSellVolume;
        double overallPressure = totalVolume > 0 ? (netFlow / totalVolume) * 100.0 : 0.0;
        overallPressure = Math.max(-100.0, Math.min(100.0, overallPressure));

        String mostBought = allAnalytics.stream()
                .max(Comparator.comparingDouble(WhaleAnalytics::getNetFlowUsd24h))
                .map(WhaleAnalytics::getSymbol)
                .orElse(null);
        String mostSold = allAnalytics.stream()
                .min(Comparator.comparingDouble(WhaleAnalytics::getNetFlowUsd24h))
                .map(WhaleAnalytics::getSymbol)
                .orElse(null);

        WhaleMarketOverview overview = new WhaleMarketOverview();
        overview.setTimestamp(Instant.now());
        overview.setTotalWhaleVolume24h(totalVolume);
        overview.setTotalBuyVolume24h(totalBuyVolume);
        overview.setTotalSellVolume24h(totalSellVolume);
        overview.setOverallPressure(overallPressure);
        overview.setOverallPressureLabel(pressureLabel(overallPressure));
        overview.setMostBought(mostBought);
        overview.setMostSold(mostSold);
        overview.setActiveSymbolCount(activeCount);
        overview.setTotalTradeCount24h(totalTradeCount);
        overview.setSymbolAnalytics(allAnalytics);

        overviewCache = new CachedEntry<>(overview);
        return overview;
    }

    public WhaleMarketOverview getMarketOverview() {
        CachedEntry<WhaleMarketOverview> cached = overviewCache;
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        return computeMarketOverview();
    }

    public List<String> getTopSymbols() {
        return TOP_SYMBOLS;
    }

    private void populateWindowStats(WhaleAnalytics analytics, String symbol,
                                     String interval, boolean isOneHour) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(STATS_SQL)) {
            stmt.setString(1, symbol);
            stmt.setString(2, interval);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    double buyVolume = rs.getDouble("buy_volume");
                    double sellVolume = rs.getDouble("sell_volume");
                    int tradeCount = rs.getInt("trade_count");

                    if (isOneHour) {
                        analytics.setBuyVolumeUsd1h(buyVolume);
                        analytics.setSellVolumeUsd1h(sellVolume);
                        analytics.setNetFlowUsd1h(buyVolume - sellVolume);
                        analytics.setTradeCount1h(tradeCount);
                        analytics.setLargestTradeUsd(rs.getDouble("largest_trade"));
                        analytics.setAvgTradeSizeUsd(rs.getDouble("avg_trade_size"));
                    } else {
                        analytics.setBuyVolumeUsd24h(buyVolume);
                        analytics.setSellVolumeUsd24h(sellVolume);
                        analytics.setNetFlowUsd24h(buyVolume - sellVolume);
                        analytics.setTradeCount24h(tradeCount);
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query whale stats for %s interval=%s", symbol, interval);
        }
    }

    private void computeDerivedMetrics(WhaleAnalytics analytics) {
        double buyVol = analytics.getBuyVolumeUsd1h();
        double sellVol = analytics.getSellVolumeUsd1h();
        double totalVol = buyVol + sellVol;

        double pressure = totalVol > 0 ? ((buyVol - sellVol) / totalVol) * 100.0 : 0.0;
        pressure = Math.max(-100.0, Math.min(100.0, pressure));
        analytics.setWhalePressure(pressure);
        analytics.setPressureLabel(pressureLabel(pressure));

        // Activity score: normalized trade count 0-100 based on baseline
        int activityScore = Math.min(100,
                (int) ((analytics.getTradeCount1h() / (double) BASELINE_TRADES_PER_HOUR) * 100));
        analytics.setWhaleActivityScore(activityScore);
    }

    private String pressureLabel(double pressure) {
        if (pressure >= 50) return "Heavy Buying";
        if (pressure >= 15) return "Buying";
        if (pressure <= -50) return "Heavy Selling";
        if (pressure <= -15) return "Selling";
        return "Neutral";
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
