package com.cryptoradar.marketdata.resource;

import com.cryptoradar.marketdata.client.BinanceClient;
import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.CryptoAsset;
import com.cryptoradar.marketdata.model.PriceSnapshot;
import com.cryptoradar.marketdata.service.BackfillService;
import com.cryptoradar.marketdata.service.MarketDataService;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/api/market")
@Produces(MediaType.APPLICATION_JSON)
public class MarketDataResource {

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
    public List<Candle> getCandles(
            @PathParam("symbol") String symbol,
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return marketDataService.getCandles(symbol, interval, limit);
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
    public BackfillService.BackfillResult triggerBackfill(
            @PathParam("symbol") String symbol,
            @PathParam("interval") String interval) {
        return backfillService.backfill(symbol.toUpperCase(), interval);
    }

    /** Trigger backfill for all symbols on a specific interval */
    @POST
    @Path("/backfill/all/{interval}")
    public List<BackfillService.BackfillResult> triggerBackfillAll(
            @PathParam("interval") String interval) {
        List<BackfillService.BackfillResult> results = new ArrayList<>();
        for (String symbol : binanceClient.getTrackedSymbols()) {
            results.add(backfillService.backfill(symbol, interval));
        }
        return results;
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
            return results;
        }
        return results;
    }

    /** Update backfill depth for an interval */
    @PUT
    @Path("/config/backfill/{interval}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateBackfillConfig(@PathParam("interval") String interval, Map<String, Object> body) {
        int depthDays = ((Number) body.get("depthDays")).intValue();
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
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
        return Response.ok(Map.of("interval", interval, "depthDays", depthDays)).build();
    }

    /** List all configured cryptos with storage stats */
    @GET
    @Path("/config/cryptos")
    public List<Map<String, Object>> getConfiguredCryptos() {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            // Get assets
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT symbol, name, rank, is_active FROM crypto_assets ORDER BY rank");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String symbol = rs.getString("symbol");
                    var entry = new java.util.HashMap<String, Object>();
                    entry.put("symbol", symbol);
                    entry.put("name", rs.getString("name"));
                    entry.put("rank", rs.getInt("rank"));
                    entry.put("isActive", rs.getBoolean("is_active"));

                    // Candle stats per interval
                    entry.put("candleStats", getCandleStats(conn, symbol));
                    entry.put("totalCandles", getTotalCandles(conn, symbol));

                    // Price snapshot count
                    entry.put("priceSnapshots", getCount(conn,
                            "SELECT COUNT(*) FROM price_snapshots WHERE symbol = ?", symbol));

                    // Whale trade count
                    entry.put("whaleTrades", getCount(conn,
                            "SELECT COUNT(*) FROM whale_transactions WHERE symbol = ?", symbol));

                    // Oldest/newest candle
                    entry.put("oldestCandle", getTimestamp(conn,
                            "SELECT MIN(time) FROM candles WHERE symbol = ?", symbol));
                    entry.put("newestCandle", getTimestamp(conn,
                            "SELECT MAX(time) FROM candles WHERE symbol = ?", symbol));

                    results.add(entry);
                }
            }
        } catch (Exception e) {
            return results;
        }
        return results;
    }

    private List<Map<String, Object>> getCandleStats(Connection conn, String symbol) {
        List<Map<String, Object>> stats = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT interval, COUNT(*) as cnt, MIN(time) as oldest, MAX(time) as newest " +
                        "FROM candles WHERE symbol = ? GROUP BY interval ORDER BY cnt DESC")) {
            stmt.setString(1, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    stats.add(Map.of(
                            "interval", rs.getString("interval"),
                            "count", rs.getLong("cnt"),
                            "oldest", rs.getTimestamp("oldest").toInstant().toString(),
                            "newest", rs.getTimestamp("newest").toInstant().toString()
                    ));
                }
            }
        } catch (Exception ignored) {}
        return stats;
    }

    private long getTotalCandles(Connection conn, String symbol) {
        return getCount(conn, "SELECT COUNT(*) FROM candles WHERE symbol = ?", symbol);
    }

    private long getCount(Connection conn, String sql, String symbol) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String getTimestamp(Connection conn, String sql, String symbol) {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    return rs.getTimestamp(1).toInstant().toString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Add a new crypto to track */
    @POST
    @Path("/config/cryptos")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addCrypto(Map<String, Object> body) {
        String symbol = ((String) body.get("symbol")).toUpperCase();
        String name = (String) body.get("name");

        if (!symbol.endsWith("USDT")) {
            symbol = symbol + "USDT";
        }

        // Validate symbol exists on Binance
        try {
            var prices = binanceClient.fetchAllPricesLightweight();
            if (!prices.containsKey(symbol)) {
                return Response.status(400).entity(Map.of("error", symbol + " not found on Binance")).build();
            }
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", "Failed to validate symbol")).build();
        }

        // Get next rank
        int nextRank = 1;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COALESCE(MAX(rank), 0) + 1 FROM crypto_assets");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) nextRank = rs.getInt(1);
        } catch (Exception e) { /* use default */ }

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
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
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
        boolean isActive = (boolean) body.get("isActive");
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
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
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
                    int deleted = stmt.executeUpdate();
                    try (PreparedStatement stmt2 = conn.prepareStatement("DELETE FROM price_snapshots WHERE symbol = ?")) {
                        stmt2.setString(1, sym);
                        stmt2.executeUpdate();
                    }
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM crypto_assets WHERE symbol = ?")) {
                stmt.setString(1, sym);
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
        binanceClient.refreshSymbolCache();
        return Response.ok(Map.of("symbol", sym, "deleted", true, "dataDeleted", deleteData)).build();
    }

    /** Search ALL Binance USDT pairs (not just tracked) */
    @GET
    @Path("/config/search")
    public List<Map<String, Object>> searchSymbols(@QueryParam("q") String query) {
        if (query == null || query.length() < 2) return List.of();
        String q = query.toUpperCase();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            // Fetch ALL Binance prices (not just tracked)
            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.binance.com/api/v3/ticker/price"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET().build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
                for (var node : root) {
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
            return results;
        }
        return results;
    }
}
