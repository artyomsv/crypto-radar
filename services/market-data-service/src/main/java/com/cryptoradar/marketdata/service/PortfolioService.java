package com.cryptoradar.marketdata.service;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PortfolioService {

    private static final Logger LOG = Logger.getLogger(PortfolioService.class);

    @Inject
    AgroalDataSource dataSource;

    public List<Map<String, Object>> getPositions() {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql = "SELECT * FROM portfolio_positions ORDER BY opened_at DESC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(mapPosition(rs));
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query portfolio positions");
        }
        return results;
    }

    public Map<String, Object> addPosition(String symbol, double entryPrice,
                                            double quantity, String side, String note) {
        String sql = "INSERT INTO portfolio_positions (symbol, entry_price, quantity, side, note) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id, opened_at";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setDouble(2, entryPrice);
            stmt.setDouble(3, quantity);
            stmt.setString(4, side);
            stmt.setString(5, note);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", rs.getInt("id"));
                    result.put("symbol", symbol);
                    result.put("entryPrice", entryPrice);
                    result.put("quantity", quantity);
                    result.put("side", side);
                    result.put("note", note);
                    result.put("isOpen", true);
                    result.put("openedAt", rs.getTimestamp("opened_at").toInstant().toString());
                    return result;
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to add portfolio position for %s", symbol);
        }
        return Map.of("error", "Failed to add position");
    }

    public Map<String, Object> closePosition(int id, double closePrice) {
        String sql = "UPDATE portfolio_positions SET is_open = false, closed_at = ?, close_price = ? "
                + "WHERE id = ? AND is_open = true";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(Instant.now()));
            stmt.setDouble(2, closePrice);
            stmt.setInt(3, id);
            int updated = stmt.executeUpdate();
            if (updated == 0) {
                return Map.of("error", "Position not found or already closed");
            }
            return Map.of("id", id, "closed", true, "closePrice", closePrice);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to close portfolio position #%d", id);
            return Map.of("error", "Failed to close position");
        }
    }

    public boolean deletePosition(int id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM portfolio_positions WHERE id = ?")) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete portfolio position #%d", id);
            return false;
        }
    }

    private Map<String, Object> mapPosition(ResultSet rs) throws java.sql.SQLException {
        HashMap<String, Object> pos = new HashMap<>();
        pos.put("id", rs.getInt("id"));
        pos.put("symbol", rs.getString("symbol"));
        pos.put("entryPrice", rs.getDouble("entry_price"));
        pos.put("quantity", rs.getDouble("quantity"));
        pos.put("side", rs.getString("side"));
        pos.put("note", rs.getString("note"));
        pos.put("isOpen", rs.getBoolean("is_open"));
        Timestamp openedAt = rs.getTimestamp("opened_at");
        pos.put("openedAt", openedAt != null ? openedAt.toInstant().toString() : null);
        Timestamp closedAt = rs.getTimestamp("closed_at");
        pos.put("closedAt", closedAt != null ? closedAt.toInstant().toString() : null);
        pos.put("closePrice", rs.getObject("close_price") != null ? rs.getDouble("close_price") : null);
        return pos;
    }
}
