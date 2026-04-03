package com.cryptoradar.analytics.service;

import com.cryptoradar.analytics.model.CorrelationMatrix;
import com.cryptoradar.analytics.model.VolatilityMetric;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CorrelationService {

    private static final Logger LOG = Logger.getLogger(CorrelationService.class);

    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    private final AgroalDataSource dataSource;
    private final IndicatorCalculator calculator;

    private final ConcurrentHashMap<String, CachedEntry<CorrelationMatrix>> correlationCache =
            new ConcurrentHashMap<>();
    private volatile CachedEntry<List<VolatilityMetric>> volatilityCache;

    public CorrelationService(AgroalDataSource dataSource, IndicatorCalculator calculator) {
        this.dataSource = dataSource;
        this.calculator = calculator;
    }

    public CorrelationMatrix getCorrelationMatrix(String interval, int days) {
        String cacheKey = interval + ":" + days;
        CachedEntry<CorrelationMatrix> cached = correlationCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        return computeCorrelationMatrix(interval, days);
    }

    public List<VolatilityMetric> getVolatility() {
        CachedEntry<List<VolatilityMetric>> cached = volatilityCache;
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        return computeVolatility();
    }

    /**
     * Computes Pearson correlation between daily returns for all symbol pairs.
     * Queries 1h candles, aggregates to daily closes, computes returns, then correlates.
     */
    CorrelationMatrix computeCorrelationMatrix(String interval, int days) {
        Map<String, List<Double>> dailyReturnsBySymbol = queryDailyReturns(interval, days);
        List<String> symbols = new ArrayList<>(dailyReturnsBySymbol.keySet());

        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();

        for (String symbolA : symbols) {
            Map<String, Double> row = new LinkedHashMap<>();
            List<Double> returnsA = dailyReturnsBySymbol.get(symbolA);

            for (String symbolB : symbols) {
                if (symbolA.equals(symbolB)) {
                    row.put(symbolB, 1.0);
                    continue;
                }
                List<Double> returnsB = dailyReturnsBySymbol.get(symbolB);
                double correlation = pearsonCorrelation(returnsA, returnsB);
                row.put(symbolB, roundTo4(correlation));
            }
            matrix.put(symbolA, row);
        }

        CorrelationMatrix result = new CorrelationMatrix();
        result.setTimestamp(Instant.now());
        result.setInterval(interval);
        result.setDays(days);
        result.setMatrix(matrix);

        correlationCache.put(interval + ":" + days, new CachedEntry<>(result));
        return result;
    }

    /**
     * Computes volatility metrics for each tracked symbol:
     * ATR(14) as % of price, Bollinger Band width, and 24h range.
     */
    List<VolatilityMetric> computeVolatility() {
        List<String> symbols = queryActiveSymbols();
        List<VolatilityMetric> metrics = new ArrayList<>();

        for (String symbol : symbols) {
            VolatilityMetric metric = computeSymbolVolatility(symbol);
            if (metric != null) {
                metrics.add(metric);
            }
        }

        // Sort by composite volatility score descending
        metrics.sort(Comparator.comparingDouble(this::compositeVolatility).reversed());

        for (int i = 0; i < metrics.size(); i++) {
            metrics.get(i).setVolatilityRank(i + 1);
        }

        volatilityCache = new CachedEntry<>(metrics);
        return metrics;
    }

    private VolatilityMetric computeSymbolVolatility(String symbol) {
        List<double[]> rows = queryCandleArrays(symbol, 200);
        if (rows.size() < 20) {
            return null;
        }

        List<Double> closes = new ArrayList<>();
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();

        for (double[] row : rows) {
            closes.add(row[0]);
            highs.add(row[1]);
            lows.add(row[2]);
        }

        double currentPrice = closes.get(closes.size() - 1);
        if (currentPrice <= 0) {
            return null;
        }

        // ATR(14) as percentage of current price
        Double atr = calculator.calculateATR(highs, lows, closes, 14);
        Double atrPct = (atr != null && atr > 0) ? (atr / currentPrice) * 100.0 : null;

        // Bollinger Band width: (upper - lower) / middle * 100
        double[] bollinger = calculator.calculateBollingerBands(closes, 20, 2.0);
        Double bollingerWidth = null;
        if (bollinger != null && bollinger[1] > 0) {
            bollingerWidth = ((bollinger[0] - bollinger[2]) / bollinger[1]) * 100.0;
        }

        // 24h range from last 24 1h candles
        Double range24hPct = compute24hRange(highs, lows, currentPrice);

        VolatilityMetric metric = new VolatilityMetric();
        metric.setSymbol(symbol);
        metric.setAtrPct(roundTo4(atrPct));
        metric.setBollingerWidth(roundTo4(bollingerWidth));
        metric.setRange24hPct(roundTo4(range24hPct));

        return metric;
    }

    private Double compute24hRange(List<Double> highs, List<Double> lows, double currentPrice) {
        int size = highs.size();
        int start = Math.max(0, size - 24);

        double high24h = Double.MIN_VALUE;
        double low24h = Double.MAX_VALUE;

        for (int i = start; i < size; i++) {
            high24h = Math.max(high24h, highs.get(i));
            low24h = Math.min(low24h, lows.get(i));
        }

        if (currentPrice <= 0) {
            return null;
        }
        return ((high24h - low24h) / currentPrice) * 100.0;
    }

    private double compositeVolatility(VolatilityMetric m) {
        double score = 0.0;
        int count = 0;
        if (m.getAtrPct() != null) {
            score += m.getAtrPct();
            count++;
        }
        if (m.getBollingerWidth() != null) {
            score += m.getBollingerWidth();
            count++;
        }
        if (m.getRange24hPct() != null) {
            score += m.getRange24hPct();
            count++;
        }
        return count > 0 ? score / count : 0.0;
    }

    /**
     * Queries daily close prices per symbol, computes daily returns.
     * Uses time_bucket to aggregate hourly candles into daily closes.
     */
    private Map<String, List<Double>> queryDailyReturns(String interval, int days) {
        Map<String, List<Double>> result = new LinkedHashMap<>();

        String sql = """
                SELECT symbol, day, close FROM (
                    SELECT symbol,
                           time_bucket('1 day', time) AS day,
                           last(close, time) AS close
                    FROM candles
                    WHERE interval = ? AND time >= NOW() - make_interval(days => ?)
                    GROUP BY symbol, day
                    ORDER BY symbol, day
                ) sub
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, interval);
            stmt.setInt(2, days);

            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, List<Double>> dailyCloses = new LinkedHashMap<>();

                while (rs.next()) {
                    String symbol = rs.getString(1);
                    double close = rs.getDouble(3);
                    dailyCloses.computeIfAbsent(symbol, k -> new ArrayList<>()).add(close);
                }

                // Convert daily closes to returns
                for (Map.Entry<String, List<Double>> entry : dailyCloses.entrySet()) {
                    List<Double> closes = entry.getValue();
                    if (closes.size() < 2) {
                        continue;
                    }

                    List<Double> returns = new ArrayList<>();
                    for (int i = 1; i < closes.size(); i++) {
                        double prev = closes.get(i - 1);
                        if (prev > 0) {
                            returns.add((closes.get(i) - prev) / prev);
                        }
                    }

                    if (!returns.isEmpty()) {
                        result.put(entry.getKey(), returns);
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query daily returns for correlation matrix");
        }

        return result;
    }

    private List<String> queryActiveSymbols() {
        List<String> symbols = new ArrayList<>();
        String sql = "SELECT DISTINCT symbol FROM candles WHERE interval = '1h' " +
                "AND time >= NOW() - INTERVAL '7 days' ORDER BY symbol";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                symbols.add(rs.getString(1));
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query active symbols");
        }
        return symbols;
    }

    /**
     * Queries candle data as arrays: [close, high, low].
     */
    private List<double[]> queryCandleArrays(String symbol, int limit) {
        List<double[]> results = new ArrayList<>();
        String sql = "SELECT close, high, low FROM (" +
                "  SELECT close, high, low, time FROM candles " +
                "  WHERE symbol = ? AND interval = '1h' " +
                "  ORDER BY time DESC LIMIT ?" +
                ") sub ORDER BY time ASC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new double[]{
                            rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)
                    });
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query candles for %s", symbol);
        }
        return results;
    }

    /**
     * Pearson correlation between two return series.
     * Aligns series to the shorter length.
     */
    private double pearsonCorrelation(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 2) {
            return 0.0;
        }

        // Align from the end (most recent data)
        int xOffset = x.size() - n;
        int yOffset = y.size() - n;

        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) {
            sumX += x.get(xOffset + i);
            sumY += y.get(yOffset + i);
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(xOffset + i) - meanX;
            double dy = y.get(yOffset + i) - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        double denominator = Math.sqrt(varX * varY);
        if (denominator == 0) {
            return 0.0;
        }
        return cov / denominator;
    }

    private Double roundTo4(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
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
