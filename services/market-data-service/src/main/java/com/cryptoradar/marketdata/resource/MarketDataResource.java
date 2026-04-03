package com.cryptoradar.marketdata.resource;

import com.cryptoradar.marketdata.client.BinanceClient;
import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.CryptoAsset;
import com.cryptoradar.marketdata.model.PriceSnapshot;
import com.cryptoradar.marketdata.service.BackfillService;
import com.cryptoradar.marketdata.service.MarketDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Path("/api/market")
@Produces(MediaType.APPLICATION_JSON)
public class MarketDataResource {

    private static final Logger LOG = Logger.getLogger(MarketDataResource.class);

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]+USDT");
    private static final Set<String> VALID_INTERVALS = Set.of(
            "1m", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "12h", "1d", "1w"
    );
    private static final int MAX_CANDLE_LIMIT = 5000;

    private static final HttpClient SEARCH_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper SEARCH_OBJECT_MAPPER = new ObjectMapper();

    @Inject
    MarketDataService marketDataService;

    @Inject
    BackfillService backfillService;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    BinanceClient binanceClient;

    @GET
    @Path("/prices")
    public List<PriceSnapshot> getLatestPrices() {
        return marketDataService.getLatestPrices();
    }

    @GET
    @Path("/candles/{symbol}")
    public Response getCandles(
            @PathParam("symbol") String symbol,
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        if (!SYMBOL_PATTERN.matcher(symbol.toUpperCase()).matches()) {
            return Response.status(400).entity(Map.of("error", "Invalid symbol format")).build();
        }
        if (!VALID_INTERVALS.contains(interval)) {
            return Response.status(400).entity(Map.of("error", "Invalid interval")).build();
        }
        int clampedLimit = Math.min(limit, MAX_CANDLE_LIMIT);
        return Response.ok(marketDataService.getCandles(symbol, interval, clampedLimit)).build();
    }

    @GET
    @Path("/assets")
    public List<CryptoAsset> getAssets() {
        return marketDataService.getAssets();
    }

    @GET
    @Path("/candles/{symbol}/latest")
    public Candle getLatestCandle(@PathParam("symbol") String symbol) {
        List<Candle> candles = marketDataService.getCandles(symbol, "1h", 1);
        return candles.isEmpty() ? null : candles.getFirst();
    }

    /** Trigger manual backfill for a specific symbol and interval */
    @POST
    @Path("/backfill/{symbol}/{interval}")
    public Response triggerBackfill(
            @PathParam("symbol") String symbol,
            @PathParam("interval") String interval) {
        if (!VALID_INTERVALS.contains(interval)) {
            return Response.status(400).entity(Map.of("error", "Invalid interval")).build();
        }
        return Response.ok(backfillService.backfill(symbol.toUpperCase(), interval)).build();
    }

    /** Trigger backfill for all symbols on a specific interval */
    @POST
    @Path("/backfill/all/{interval}")
    public Response triggerBackfillAll(
            @PathParam("interval") String interval) {
        if (!VALID_INTERVALS.contains(interval)) {
            return Response.status(400).entity(Map.of("error", "Invalid interval")).build();
        }
        List<BackfillService.BackfillResult> results = new ArrayList<>();
        for (String symbol : binanceClient.getTrackedSymbols()) {
            results.add(backfillService.backfill(symbol, interval));
        }
        return Response.ok(results).build();
    }

    /** Get available intervals */
    @GET
    @Path("/intervals")
    public Map<String, Object> getIntervals() {
        return Map.of(
                "intervals", List.of("1m", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "12h", "1d", "1w"),
                "default", "1h"
        );
    }

    // --- Crypto Configuration CRUD ---

    /** Get backfill depth config for all intervals */
    @GET
    @Path("/config/backfill")
    public List<Map<String, Object>> getBackfillConfig() {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT interval, depth_days, description FROM backfill_config ORDER BY depth_days");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(Map.of(
                        "interval", rs.getString("interval"),
                        "depthDays", rs.getInt("depth_days"),
                        "description", rs.getString("description") != null ? rs.getString("description") : ""
                ));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to load backfill config: %s", e.getMessage());
            return results;
        }
        return results;
    }

    /** Update backfill depth for an interval */
    @PUT
    @Path("/config/backfill/{interval}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateBackfillConfig(@PathParam("interval") String interval, Map<String, Object> body) {
        if (!VALID_INTERVALS.contains(interval)) {
            return Response.status(400).entity(Map.of("error", "Invalid interval")).build();
        }
        Object depthDaysObj = body.get("depthDays");
        if (depthDaysObj == null) {
            return Response.status(400).entity(Map.of("error", "depthDays is required")).build();
        }
        int depthDays = ((Number) depthDaysObj).intValue();
        if (depthDays < 1 || depthDays > 5000) {
            return Response.status(400).entity(Map.of("error", "depthDays must be 1-5000")).build();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE backfill_config SET depth_days = ? WHERE interval = ?")) {
            stmt.setInt(1, depthDays);
            stmt.setString(2, interval);
            int updated = stmt.executeUpdate();
            if (updated == 0) {
                return Response.status(404).entity(Map.of("error", "Interval not found")).build();
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update backfill config for interval %s", interval);
            return Response.status(500).entity(Map.of("error", "Internal server error")).build();
        }
        return Response.ok(Map.of("interval", interval, "depthDays", depthDays)).build();
    }

    /** List all configured cryptos with storage stats */
    @GET
    @Path("/config/cryptos")
    public List<Map<String, Object>> getConfiguredCryptos() {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            // Batch: candle stats per symbol+interval
            Map<String, List<Map<String, Object>>> candleStatsMap = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT symbol, interval, COUNT(*) as cnt, MIN(time) as oldest, MAX(time) as newest " +
                            "FROM candles GROUP BY symbol, interval ORDER BY cnt DESC");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String sym = rs.getString("symbol");
                    Timestamp oldest = rs.getTimestamp("oldest");
                    Timestamp newest = rs.getTimestamp("newest");
                    candleStatsMap.computeIfAbsent(sym, k -> new ArrayList<>()).add(Map.of(
                            "interval", rs.getString("interval"),
                            "count", rs.getLong("cnt"),
                            "oldest", oldest != null ? oldest.toInstant().toString() : "",
                            "newest", newest != null ? newest.toInstant().toString() : ""
                    ));
                }
            }

            // Batch: total candles, price snapshots, whale transactions per symbol
            Map<String, Long> totalCandlesMap = loadCountsBySymbol(conn,
                    "SELECT symbol, COUNT(*) as total FROM candles GROUP BY symbol");
            Map<String, Long> priceSnapshotsMap = loadCountsBySymbol(conn,
                    "SELECT symbol, COUNT(*) as total FROM price_snapshots GROUP BY symbol");
            Map<String, Long> whaleTradesMap = loadCountsBySymbol(conn,
                    "SELECT symbol, COUNT(*) as total FROM whale_transactions GROUP BY symbol");

            // Batch: oldest/newest candle per symbol
            Map<String, String> oldestCandleMap = loadTimestampsBySymbol(conn,
                    "SELECT symbol, MIN(time) as ts FROM candles GROUP BY symbol");
            Map<String, String> newestCandleMap = loadTimestampsBySymbol(conn,
                    "SELECT symbol, MAX(time) as ts FROM candles GROUP BY symbol");

            // Get assets and assemble results
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT symbol, name, rank, is_active FROM crypto_assets ORDER BY rank");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String symbol = rs.getString("symbol");
                    HashMap<String, Object> entry = new HashMap<>();
                    entry.put("symbol", symbol);
                    entry.put("name", rs.getString("name"));
                    entry.put("rank", rs.getInt("rank"));
                    entry.put("isActive", rs.getBoolean("is_active"));
                    entry.put("candleStats", candleStatsMap.getOrDefault(symbol, List.of()));
                    entry.put("totalCandles", totalCandlesMap.getOrDefault(symbol, 0L));
                    entry.put("priceSnapshots", priceSnapshotsMap.getOrDefault(symbol, 0L));
                    entry.put("whaleTrades", whaleTradesMap.getOrDefault(symbol, 0L));
                    entry.put("oldestCandle", oldestCandleMap.get(symbol));
                    entry.put("newestCandle", newestCandleMap.get(symbol));
                    results.add(entry);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to load configured cryptos: %s", e.getMessage());
            return results;
        }
        return results;
    }

    private Map<String, Long> loadCountsBySymbol(Connection conn, String sql) {
        Map<String, Long> map = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString("symbol"), rs.getLong("total"));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to load counts [%s]: %s", sql, e.getMessage());
        }
        return map;
    }

    private Map<String, String> loadTimestampsBySymbol(Connection conn, String sql) {
        Map<String, String> map = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("ts");
                if (ts != null) {
                    map.put(rs.getString("symbol"), ts.toInstant().toString());
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to load timestamps [%s]: %s", sql, e.getMessage());
        }
        return map;
    }

    /** Add a new crypto to track */
    @POST
    @Path("/config/cryptos")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addCrypto(Map<String, Object> body) {
        Object symbolObj = body.get("symbol");
        if (symbolObj == null) {
            return Response.status(400).entity(Map.of("error", "symbol is required")).build();
        }
        String symbol = ((String) symbolObj).toUpperCase();
        String name = (String) body.get("name");

        if (!symbol.endsWith("USDT")) {
            symbol = symbol + "USDT";
        }

        if (!SYMBOL_PATTERN.matcher(symbol).matches()) {
            return Response.status(400).entity(Map.of("error", "Invalid symbol format")).build();
        }

        // Validate symbol exists on Binance
        try {
            Map<String, Double> prices = binanceClient.fetchAllPricesLightweight();
            if (!prices.containsKey(symbol)) {
                return Response.status(400).entity(Map.of("error", symbol + " not found on Binance")).build();
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to validate symbol %s on Binance", symbol);
            return Response.status(500).entity(Map.of("error", "Failed to validate symbol")).build();
        }

        // Get next rank
        int nextRank = 1;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COALESCE(MAX(rank), 0) + 1 FROM crypto_assets");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) nextRank = rs.getInt(1);
        } catch (Exception e) {
            LOG.warnf("Failed to determine next rank, using default: %s", e.getMessage());
        }

        // Insert
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO crypto_assets (symbol, name, rank, is_active) VALUES (?, ?, ?, true) " +
                             "ON CONFLICT (symbol) DO UPDATE SET name = EXCLUDED.name, is_active = true")) {
            stmt.setString(1, symbol);
            stmt.setString(2, name != null ? name : symbol.replace("USDT", ""));
            stmt.setInt(3, nextRank);
            stmt.executeUpdate();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to add crypto %s", symbol);
            return Response.status(500).entity(Map.of("error", "Internal server error")).build();
        }

        binanceClient.refreshSymbolCache();

        // Trigger backfill for new symbol
        String finalSymbol = symbol;
        Thread.ofVirtual().start(() -> {
            for (String interval : List.of("1h", "1d", "4h", "15m")) {
                backfillService.backfill(finalSymbol, interval);
            }
        });

        return Response.ok(Map.of("symbol", symbol, "status", "added", "backfillStarted", true)).build();
    }

    /** Toggle crypto active/inactive */
    @PUT
    @Path("/config/cryptos/{symbol}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response toggleCrypto(@PathParam("symbol") String symbol, Map<String, Object> body) {
        Object isActiveObj = body.get("isActive");
        if (isActiveObj == null) {
            return Response.status(400).entity(Map.of("error", "isActive is required")).build();
        }
        boolean isActive = (boolean) isActiveObj;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE crypto_assets SET is_active = ? WHERE symbol = ?")) {
            stmt.setBoolean(1, isActive);
            stmt.setString(2, symbol.toUpperCase());
            int updated = stmt.executeUpdate();
            if (updated == 0) {
                return Response.status(404).entity(Map.of("error", "Symbol not found")).build();
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to toggle crypto %s", symbol);
            return Response.status(500).entity(Map.of("error", "Internal server error")).build();
        }
        binanceClient.refreshSymbolCache();
        return Response.ok(Map.of("symbol", symbol, "isActive", isActive)).build();
    }

    /** Remove a crypto completely (deactivates, optionally deletes data) */
    @DELETE
    @Path("/config/cryptos/{symbol}")
    public Response removeCrypto(@PathParam("symbol") String symbol,
                                  @QueryParam("deleteData") @DefaultValue("false") boolean deleteData) {
        String sym = symbol.toUpperCase();
        try (Connection conn = dataSource.getConnection()) {
            if (deleteData) {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM candles WHERE symbol = ?")) {
                    stmt.setString(1, sym);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM price_snapshots WHERE symbol = ?")) {
                    stmt.setString(1, sym);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM backfill_status WHERE symbol = ?")) {
                    stmt.setString(1, sym);
                    stmt.executeUpdate();
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM crypto_assets WHERE symbol = ?")) {
                stmt.setString(1, sym);
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to remove crypto %s", sym);
            return Response.status(500).entity(Map.of("error", "Internal server error")).build();
        }
        binanceClient.refreshSymbolCache();
        return Response.ok(Map.of("symbol", sym, "deleted", true, "dataDeleted", deleteData)).build();
    }

    /** Search ALL Binance USDT pairs (not just tracked) */
    @GET
    @Path("/config/search")
    public List<Map<String, Object>> searchSymbols(@QueryParam("q") String query) {
        if (query == null || query.length() < 2) return List.of();
        String q = URLEncoder.encode(query.toUpperCase(), StandardCharsets.UTF_8);
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.binance.com/api/v3/ticker/price"))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> response = SEARCH_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = SEARCH_OBJECT_MAPPER.readTree(response.body());
                for (JsonNode node : root) {
                    String sym = node.get("symbol").asText();
                    if (sym.endsWith("USDT") && sym.contains(q)) {
                        results.add(Map.of(
                                "symbol", sym,
                                "price", node.get("price").asDouble()
                        ));
                        if (results.size() >= 20) break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to search Binance symbols for query '%s': %s", query, e.getMessage());
            return results;
        }
        return results;
    }
}
