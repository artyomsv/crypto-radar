package com.cryptoradar.derivatives.service;

import com.cryptoradar.derivatives.client.BinanceFuturesClient;
import com.cryptoradar.derivatives.event.RedisEventPublisher;
import com.cryptoradar.derivatives.model.DerivativesOverview;
import com.cryptoradar.derivatives.model.FundingRate;
import com.cryptoradar.derivatives.model.Liquidation;
import com.cryptoradar.derivatives.model.LongShortRatio;
import com.cryptoradar.derivatives.model.OpenInterest;
import com.cryptoradar.derivatives.model.SymbolDerivatives;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DerivativesService {

    private static final Logger LOG = Logger.getLogger(DerivativesService.class);

    private static final long OVERVIEW_CACHE_TTL_MS = 30_000;

    @Inject
    BinanceFuturesClient futuresClient;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    RedisEventPublisher redisPublisher;

    // In-memory caches for latest data per symbol
    private final Map<String, FundingRate> fundingRateCache = new ConcurrentHashMap<>();
    private final Map<String, OpenInterest> openInterestCache = new ConcurrentHashMap<>();
    private final Map<String, LongShortRatio> longShortCache = new ConcurrentHashMap<>();

    // Overview cache
    private volatile DerivativesOverview cachedOverview;
    private volatile long lastOverviewCompute = 0;

    /**
     * Fetch and cache all funding rates (single API call).
     * Also stores snapshots in TimescaleDB for historical tracking.
     */
    public void refreshFundingRates() {
        List<FundingRate> rates = futuresClient.fetchFundingRates();
        if (rates.isEmpty()) return;

        for (FundingRate rate : rates) {
            fundingRateCache.put(rate.getSymbol(), rate);
        }
        storeFundingRates(rates);
        LOG.infof("Refreshed funding rates for %d symbols", rates.size());
    }

    /**
     * Fetch and cache open interest for all tracked symbols.
     * Batched with 100ms delay between requests.
     */
    public void refreshOpenInterest() {
        Set<String> symbols = futuresClient.getTrackedSymbols();
        int count = 0;
        for (String symbol : symbols) {
            OpenInterest oi = futuresClient.fetchOpenInterest(symbol);
            if (oi != null) {
                openInterestCache.put(symbol, oi);
                storeOpenInterest(oi);
                count++;
            }
            sleepBetweenRequests();
        }
        LOG.infof("Refreshed open interest for %d/%d symbols", count, symbols.size());
    }

    /**
     * Fetch and cache long/short ratio for all tracked symbols.
     */
    public void refreshLongShortRatios() {
        Set<String> symbols = futuresClient.getTrackedSymbols();
        int count = 0;
        for (String symbol : symbols) {
            LongShortRatio lsr = futuresClient.fetchLongShortRatio(symbol);
            if (lsr != null) {
                longShortCache.put(symbol, lsr);
                count++;
            }
            sleepBetweenRequests();
        }
        LOG.infof("Refreshed long/short ratios for %d/%d symbols", count, symbols.size());
    }

    /**
     * Return cached overview or compute fresh if cache expired.
     */
    public DerivativesOverview getOverview() {
        long now = System.currentTimeMillis();
        if (cachedOverview != null && now - lastOverviewCompute < OVERVIEW_CACHE_TTL_MS) {
            return cachedOverview;
        }
        return computeOverview();
    }

    /**
     * Compute full market derivatives overview from cached data.
     */
    public DerivativesOverview computeOverview() {
        DerivativesOverview overview = new DerivativesOverview();
        overview.setTimestamp(Instant.now());

        Set<String> symbols = futuresClient.getTrackedSymbols();
        List<SymbolDerivatives> symbolDataList = new ArrayList<>();

        double totalOiUsd = 0;
        double fundingSum = 0;
        int fundingCount = 0;
        double totalLongPct = 0;
        double totalShortPct = 0;
        int lsCount = 0;

        for (String symbol : symbols) {
            SymbolDerivatives sd = buildSymbolDerivatives(symbol);
            symbolDataList.add(sd);

            totalOiUsd += sd.getOpenInterestUsd();

            if (sd.getFundingRate() != 0) {
                fundingSum += sd.getFundingRate();
                fundingCount++;
            }
            if (sd.getLongPct() > 0 || sd.getShortPct() > 0) {
                totalLongPct += sd.getLongPct();
                totalShortPct += sd.getShortPct();
                lsCount++;
            }
        }

        overview.setTotalOpenInterestUsd(totalOiUsd);
        overview.setSymbolData(symbolDataList);

        // Average funding rate
        double avgFunding = fundingCount > 0 ? fundingSum / fundingCount : 0;
        overview.setAvgFundingRate(avgFunding);
        overview.setFundingRateLabel(classifyFundingRate(avgFunding));

        // Aggregated long/short percentages
        if (lsCount > 0) {
            overview.setMarketLongPct(totalLongPct / lsCount * 100);
            overview.setMarketShortPct(totalShortPct / lsCount * 100);
        }

        // 24h liquidation totals from DB
        loadLiquidationTotals(overview);

        cachedOverview = overview;
        lastOverviewCompute = System.currentTimeMillis();
        return overview;
    }

    /**
     * Get derivatives data for a single symbol.
     */
    public SymbolDerivatives getSymbolDerivatives(String symbol) {
        return buildSymbolDerivatives(symbol.toUpperCase());
    }

    /**
     * Get all current funding rates from cache.
     */
    public List<FundingRate> getAllFundingRates() {
        return new ArrayList<>(fundingRateCache.values());
    }

    /**
     * Record a liquidation event: store in DB and publish to Redis.
     */
    public void recordLiquidation(Liquidation liq) {
        storeLiquidation(liq);
        redisPublisher.publishLiquidation(liq);
    }

    /**
     * Get recent liquidations from DB.
     */
    public List<Liquidation> getRecentLiquidations(int limit) {
        int clampedLimit = Math.min(limit, 500);
        List<Liquidation> liquidations = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT symbol, side, price, quantity, value_usd, time " +
                             "FROM derivatives_liquidations ORDER BY time DESC LIMIT ?")) {
            stmt.setInt(1, clampedLimit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    liquidations.add(new Liquidation(
                            rs.getString("symbol"),
                            rs.getString("side"),
                            rs.getDouble("price"),
                            rs.getDouble("quantity"),
                            rs.getDouble("value_usd"),
                            rs.getTimestamp("time").toInstant()
                    ));
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query recent liquidations");
        }
        return liquidations;
    }

    private SymbolDerivatives buildSymbolDerivatives(String symbol) {
        SymbolDerivatives sd = new SymbolDerivatives();
        sd.setSymbol(symbol);

        FundingRate fr = fundingRateCache.get(symbol);
        if (fr != null) {
            sd.setFundingRate(fr.getFundingRate());
            // Annualized: fundingRate * 3 (per day) * 365 * 100 (to percentage)
            sd.setFundingRateAnnualized(fr.getFundingRate() * 3 * 365 * 100);
        }

        OpenInterest oi = openInterestCache.get(symbol);
        if (oi != null) {
            sd.setOpenInterestUsd(oi.getOpenInterestValue());
        }

        LongShortRatio lsr = longShortCache.get(symbol);
        if (lsr != null) {
            sd.setLongShortRatio(lsr.getLongShortRatio());
            sd.setLongPct(lsr.getLongAccount());
            sd.setShortPct(lsr.getShortAccount());
        }

        // 24h liquidations for this symbol from DB
        sd.setLiquidations24hUsd(querySymbolLiquidations24h(symbol));

        // Derive sentiment from funding rate + long/short ratio
        sd.setSentiment(deriveSentiment(sd.getFundingRate(), sd.getLongShortRatio()));

        return sd;
    }

    private String deriveSentiment(double fundingRate, double longShortRatio) {
        // High funding + more longs = bullish crowd
        if (fundingRate > 0.001 && longShortRatio > 1.5) return "Extreme Bullish";
        if (fundingRate > 0.0005 && longShortRatio > 1.2) return "Bullish";
        if (fundingRate < -0.001 && longShortRatio < 0.7) return "Extreme Bearish";
        if (fundingRate < -0.0005 && longShortRatio < 0.85) return "Bearish";
        return "Neutral";
    }

    private String classifyFundingRate(double avgFunding) {
        if (avgFunding > 0.001) return "Extreme Bullish";
        if (avgFunding > 0.0003) return "Bullish";
        if (avgFunding < -0.001) return "Extreme Bearish";
        if (avgFunding < -0.0003) return "Bearish";
        return "Neutral";
    }

    private void loadLiquidationTotals(DerivativesOverview overview) {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT side, COALESCE(SUM(value_usd), 0) as total " +
                             "FROM derivatives_liquidations WHERE time >= ? GROUP BY side")) {
            stmt.setTimestamp(1, Timestamp.from(since));
            double totalLong = 0;
            double totalShort = 0;
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String side = rs.getString("side");
                    double total = rs.getDouble("total");
                    if ("BUY".equals(side)) {
                        totalLong = total;
                    } else {
                        totalShort = total;
                    }
                }
            }
            overview.setLongLiquidations24h(totalLong);
            overview.setShortLiquidations24h(totalShort);
            overview.setTotalLiquidations24h(totalLong + totalShort);
        } catch (Exception e) {
            LOG.debugf("Failed to load 24h liquidation totals: %s", e.getMessage());
        }
    }

    private double querySymbolLiquidations24h(String symbol) {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COALESCE(SUM(value_usd), 0) FROM derivatives_liquidations " +
                             "WHERE symbol = ? AND time >= ?")) {
            stmt.setString(1, symbol);
            stmt.setTimestamp(2, Timestamp.from(since));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (Exception e) {
            LOG.debugf("Failed to query 24h liquidations for %s: %s", symbol, e.getMessage());
        }
        return 0;
    }

    private void storeFundingRates(List<FundingRate> rates) {
        String sql = "INSERT INTO derivatives_funding_rates " +
                "(time, symbol, funding_rate, mark_price, index_price, next_funding_time) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (time, symbol) DO UPDATE SET " +
                "funding_rate = EXCLUDED.funding_rate, mark_price = EXCLUDED.mark_price, " +
                "index_price = EXCLUDED.index_price, next_funding_time = EXCLUDED.next_funding_time";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (FundingRate rate : rates) {
                stmt.setTimestamp(1, Timestamp.from(rate.getFundingTime()));
                stmt.setString(2, rate.getSymbol());
                stmt.setDouble(3, rate.getFundingRate());
                stmt.setDouble(4, rate.getMarkPrice());
                stmt.setDouble(5, rate.getIndexPrice());
                stmt.setTimestamp(6, Timestamp.from(rate.getNextFundingTime()));
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (Exception e) {
            LOG.warnf("Failed to store funding rates: %s", e.getMessage());
        }
    }

    private void storeOpenInterest(OpenInterest oi) {
        String sql = "INSERT INTO derivatives_open_interest " +
                "(time, symbol, open_interest, open_interest_usd) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (time, symbol) DO UPDATE SET " +
                "open_interest = EXCLUDED.open_interest, open_interest_usd = EXCLUDED.open_interest_usd";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(oi.getTimestamp()));
            stmt.setString(2, oi.getSymbol());
            stmt.setDouble(3, oi.getOpenInterest());
            stmt.setDouble(4, oi.getOpenInterestValue());
            stmt.executeUpdate();
        } catch (Exception e) {
            LOG.warnf("Failed to store open interest for %s: %s", oi.getSymbol(), e.getMessage());
        }
    }

    private void storeLiquidation(Liquidation liq) {
        String sql = "INSERT INTO derivatives_liquidations " +
                "(time, symbol, side, price, quantity, value_usd) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(liq.getTime()));
            stmt.setString(2, liq.getSymbol());
            stmt.setString(3, liq.getSide());
            stmt.setDouble(4, liq.getPrice());
            stmt.setDouble(5, liq.getQuantity());
            stmt.setDouble(6, liq.getValueUsd());
            stmt.executeUpdate();
        } catch (Exception e) {
            LOG.warnf("Failed to store liquidation for %s: %s", liq.getSymbol(), e.getMessage());
        }
    }

    private void sleepBetweenRequests() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
