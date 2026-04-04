package com.cryptoradar.whale.service;

import com.cryptoradar.whale.event.RedisEventPublisher;
import com.cryptoradar.whale.model.WhaleFlowSummary;
import com.cryptoradar.whale.model.WhaleTransaction;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class WhaleFlowService {

    private static final Logger LOG = Logger.getLogger(WhaleFlowService.class);

    private static final String INSERT_SQL = """
            INSERT INTO whale_transactions (time, symbol, price, quantity, value_usd, side, source,
                trade_id, from_address, to_address, from_label, to_label, blockchain, tx_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RECENT_SQL = """
            SELECT time, symbol, price, quantity, value_usd, side, source,
                   trade_id, from_address, to_address, from_label, to_label, blockchain, tx_hash
            FROM whale_transactions
            WHERE symbol = ? AND time >= NOW() - ?::interval
            ORDER BY time DESC
            LIMIT ?
            """;

    private static final String SELECT_ALL_RECENT_SQL = """
            SELECT time, symbol, price, quantity, value_usd, side, source,
                   trade_id, from_address, to_address, from_label, to_label, blockchain, tx_hash
            FROM whale_transactions
            WHERE time >= NOW() - ?::interval
            ORDER BY time DESC
            LIMIT ?
            """;

    private static final String FLOW_SUMMARY_SQL = """
            SELECT
                time_bucket(?::interval, time) AS bucket,
                symbol,
                COUNT(*) FILTER (WHERE side = 'BUY') AS buy_count,
                COUNT(*) FILTER (WHERE side = 'SELL') AS sell_count,
                COALESCE(SUM(value_usd) FILTER (WHERE side = 'BUY'), 0) AS buy_volume,
                COALESCE(SUM(value_usd) FILTER (WHERE side = 'SELL'), 0) AS sell_volume,
                AVG(value_usd) AS avg_trade_size,
                MAX(value_usd) AS largest_trade
            FROM whale_transactions
            WHERE symbol = ? AND time > NOW() - ?::interval
            GROUP BY bucket, symbol
            ORDER BY bucket DESC
            """;

    private final AgroalDataSource dataSource;
    private final RedisEventPublisher redisPublisher;

    public WhaleFlowService(AgroalDataSource dataSource, RedisEventPublisher redisPublisher) {
        this.dataSource = dataSource;
        this.redisPublisher = redisPublisher;
    }

    public void processWhaleTransaction(WhaleTransaction tx) {
        storeTransaction(tx);
        redisPublisher.publishWhaleTransaction(tx);
        LOG.infof("Whale detected: %s %s %.4f @ $%.2f = $%.0f [%s]",
                tx.getSide(), tx.getSymbol(), tx.getQuantity(), tx.getPrice(), tx.getValueUsd(), tx.getSource());
    }

    /**
     * Store only if not a duplicate (for Whale Alert overlapping windows).
     * Returns true if the transaction was new and stored.
     */
    public boolean processWhaleTransactionIfNew(WhaleTransaction tx) {
        if (tx.getTxHash() != null && !tx.getTxHash().isBlank()) {
            // Dedup by tx_hash for blockchain transactions
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT COUNT(*) FROM whale_transactions WHERE tx_hash = ?")) {
                stmt.setString(1, tx.getTxHash());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) return false;
                }
            } catch (Exception e) {
                LOG.debugf("Dedup check failed for tx_hash=%s: %s", tx.getTxHash(), e.getMessage());
            }
        }
        processWhaleTransaction(tx);
        return true;
    }

    public List<WhaleTransaction> getRecentTransactions(String symbol, int limit, String period) {
        String interval = mapPeriodToInterval(period);
        List<WhaleTransaction> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_RECENT_SQL)) {
            stmt.setString(1, symbol);
            stmt.setString(2, interval);
            stmt.setInt(3, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapTransaction(rs));
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query recent whale transactions for %s", symbol);
        }
        return results;
    }

    public List<WhaleTransaction> getAllRecentTransactions(int limit, String period) {
        String interval = mapPeriodToInterval(period);
        List<WhaleTransaction> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_RECENT_SQL)) {
            stmt.setString(1, interval);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapTransaction(rs));
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query all recent whale transactions");
        }
        return results;
    }

    public List<WhaleFlowSummary> getFlowSummary(String symbol, String window) {
        String bucketInterval = mapWindowToBucket(window);
        List<WhaleFlowSummary> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FLOW_SUMMARY_SQL)) {
            stmt.setString(1, bucketInterval);
            stmt.setString(2, symbol);
            stmt.setString(3, window);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapFlowSummary(rs));
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query flow summary for %s window=%s", symbol, window);
        }
        return results;
    }

    private void storeTransaction(WhaleTransaction tx) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            stmt.setTimestamp(1, Timestamp.from(tx.getTime()));
            stmt.setString(2, tx.getSymbol());
            stmt.setDouble(3, tx.getPrice());
            stmt.setDouble(4, tx.getQuantity());
            stmt.setDouble(5, tx.getValueUsd());
            stmt.setString(6, tx.getSide());
            stmt.setString(7, tx.getSource());
            stmt.setString(8, tx.getTradeId());
            stmt.setString(9, tx.getFromAddress());
            stmt.setString(10, tx.getToAddress());
            stmt.setString(11, tx.getFromLabel());
            stmt.setString(12, tx.getToLabel());
            stmt.setString(13, tx.getBlockchain());
            stmt.setString(14, tx.getTxHash());
            stmt.executeUpdate();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to store whale transaction for %s", tx.getSymbol());
        }
    }

    private WhaleTransaction mapTransaction(ResultSet rs) throws java.sql.SQLException {
        return new WhaleTransaction(
                rs.getTimestamp("time").toInstant(),
                rs.getString("symbol"),
                rs.getDouble("price"),
                rs.getDouble("quantity"),
                rs.getDouble("value_usd"),
                rs.getString("side"),
                rs.getString("source"),
                rs.getString("trade_id"),
                rs.getString("from_address"),
                rs.getString("to_address"),
                rs.getString("from_label"),
                rs.getString("to_label"),
                rs.getString("blockchain"),
                rs.getString("tx_hash")
        );
    }

    private WhaleFlowSummary mapFlowSummary(ResultSet rs) throws java.sql.SQLException {
        double buyVolume = rs.getDouble("buy_volume");
        double sellVolume = rs.getDouble("sell_volume");
        double netFlow = buyVolume - sellVolume;
        double totalVolume = buyVolume + sellVolume;
        double pressure = totalVolume > 0 ? (netFlow / totalVolume) * 100.0 : 0.0;
        pressure = Math.max(-100.0, Math.min(100.0, pressure));

        return new WhaleFlowSummary(
                rs.getTimestamp("bucket").toInstant(),
                rs.getString("symbol"),
                rs.getInt("buy_count"),
                rs.getInt("sell_count"),
                buyVolume,
                sellVolume,
                netFlow,
                rs.getDouble("avg_trade_size"),
                rs.getDouble("largest_trade"),
                pressure
        );
    }

    private static final String DISTRIBUTION_SQL = """
            SELECT
                CASE WHEN source = 'whale-alert' THEN 'onchain' ELSE 'exchange' END AS source_type,
                COALESCE(SUM(value_usd) FILTER (WHERE side = 'BUY'), 0) AS buy_volume,
                COALESCE(SUM(value_usd) FILTER (WHERE side = 'SELL'), 0) AS sell_volume,
                COUNT(*) FILTER (WHERE side = 'BUY') AS buy_count,
                COUNT(*) FILTER (WHERE side = 'SELL') AS sell_count
            FROM whale_transactions
            WHERE time > NOW() - ?::interval
            GROUP BY source_type
            """;

    public Map<String, Object> getDistribution(String window) {
        String interval = mapWindowToInterval(window);
        Map<String, Object> exchange = emptyDistSide();
        Map<String, Object> onchain = emptyDistSide();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DISTRIBUTION_SQL)) {
            stmt.setString(1, interval);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("source_type");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("buyVolume", rs.getDouble("buy_volume"));
                    row.put("sellVolume", rs.getDouble("sell_volume"));
                    row.put("buyCount", rs.getInt("buy_count"));
                    row.put("sellCount", rs.getInt("sell_count"));
                    if ("exchange".equals(type)) exchange = row;
                    else onchain = row;
                }
            }
        } catch (java.sql.SQLException e) {
            LOG.errorf(e, "Failed to query distribution for window=%s", window);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", window);
        result.put("exchange", exchange);
        result.put("onchain", onchain);
        return result;
    }

    private Map<String, Object> emptyDistSide() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buyVolume", 0.0);
        m.put("sellVolume", 0.0);
        m.put("buyCount", 0);
        m.put("sellCount", 0);
        return m;
    }

    private String mapWindowToInterval(String window) {
        return switch (window) {
            case "1h" -> "1 hour";
            case "2h" -> "2 hours";
            case "4h" -> "4 hours";
            case "8h" -> "8 hours";
            case "12h" -> "12 hours";
            case "24h" -> "24 hours";
            default -> "1 hour";
        };
    }

    private String mapPeriodToInterval(String period) {
        return switch (period) {
            case "1d" -> "1 day";
            case "1w" -> "7 days";
            case "2w" -> "14 days";
            case "1m" -> "30 days";
            case "3m" -> "90 days";
            case "6m" -> "180 days";
            case "1y" -> "365 days";
            default -> "1 day";
        };
    }

    private String mapWindowToBucket(String window) {
        return switch (window) {
            case "1h" -> "15 minutes";
            case "4h" -> "30 minutes";
            case "24h" -> "1 hour";
            default -> "15 minutes";
        };
    }
}
