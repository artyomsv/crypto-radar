package com.cryptoradar.marketdata.service;

import com.cryptoradar.marketdata.event.RedisEventPublisher;
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
public class AlertService {

    private static final Logger LOG = Logger.getLogger(AlertService.class);

    @Inject
    AgroalDataSource dataSource;

    @Inject
    RedisEventPublisher redisEventPublisher;

    public void checkAlerts(String symbol, double currentPrice) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, symbol, condition, target_price, note " +
                             "FROM price_alerts " +
                             "WHERE is_active = true AND is_triggered = false AND symbol = ?")) {
            stmt.setString(1, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String condition = rs.getString("condition");
                    double targetPrice = rs.getDouble("target_price");
                    String note = rs.getString("note");

                    boolean triggered = isConditionMet(condition, currentPrice, targetPrice);
                    if (!triggered) continue;

                    markTriggered(conn, id);
                    publishTriggeredAlert(id, symbol, condition, targetPrice, currentPrice, note);
                    LOG.infof("Alert #%d triggered: %s %s %.2f (current: %.2f)",
                            id, symbol, condition, targetPrice, currentPrice);
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to check alerts for %s", symbol);
        }
    }

    private boolean isConditionMet(String condition, double currentPrice, double targetPrice) {
        return switch (condition) {
            case "ABOVE" -> currentPrice >= targetPrice;
            case "BELOW" -> currentPrice <= targetPrice;
            default -> false;
        };
    }

    private void markTriggered(Connection conn, int id) {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE price_alerts SET is_triggered = true, triggered_at = ? WHERE id = ?")) {
            stmt.setTimestamp(1, Timestamp.from(Instant.now()));
            stmt.setInt(2, id);
            stmt.executeUpdate();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to mark alert #%d as triggered", id);
        }
    }

    private void publishTriggeredAlert(int id, String symbol, String condition,
                                       double targetPrice, double currentPrice, String note) {
        Map<String, Object> alert = new HashMap<>();
        alert.put("id", id);
        alert.put("symbol", symbol);
        alert.put("condition", condition);
        alert.put("targetPrice", targetPrice);
        alert.put("currentPrice", currentPrice);
        alert.put("note", note);
        alert.put("triggeredAt", Instant.now().toString());
        redisEventPublisher.publishAlert(alert);
    }

    public List<Map<String, Object>> getAlerts() {
        return queryAlerts("SELECT * FROM price_alerts ORDER BY created_at DESC");
    }

    public List<Map<String, Object>> getActiveAlerts() {
        return queryAlerts(
                "SELECT * FROM price_alerts WHERE is_active = true AND is_triggered = false ORDER BY created_at DESC");
    }

    private List<Map<String, Object>> queryAlerts(String sql) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(mapAlert(rs));
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to query alerts");
        }
        return results;
    }

    private Map<String, Object> mapAlert(ResultSet rs) throws java.sql.SQLException {
        HashMap<String, Object> alert = new HashMap<>();
        alert.put("id", rs.getInt("id"));
        alert.put("symbol", rs.getString("symbol"));
        alert.put("condition", rs.getString("condition"));
        alert.put("targetPrice", rs.getDouble("target_price"));
        alert.put("isActive", rs.getBoolean("is_active"));
        alert.put("isTriggered", rs.getBoolean("is_triggered"));
        Timestamp triggeredAt = rs.getTimestamp("triggered_at");
        alert.put("triggeredAt", triggeredAt != null ? triggeredAt.toInstant().toString() : null);
        Timestamp createdAt = rs.getTimestamp("created_at");
        alert.put("createdAt", createdAt != null ? createdAt.toInstant().toString() : null);
        alert.put("note", rs.getString("note"));
        return alert;
    }

    public Map<String, Object> createAlert(String symbol, String condition, double targetPrice, String note) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO price_alerts (symbol, condition, target_price, note) " +
                             "VALUES (?, ?, ?, ?) RETURNING id, created_at")) {
            stmt.setString(1, symbol);
            stmt.setString(2, condition);
            stmt.setDouble(3, targetPrice);
            stmt.setString(4, note);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", rs.getInt("id"));
                    result.put("symbol", symbol);
                    result.put("condition", condition);
                    result.put("targetPrice", targetPrice);
                    result.put("note", note);
                    result.put("isActive", true);
                    result.put("isTriggered", false);
                    result.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return result;
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create alert for %s", symbol);
        }
        return Map.of("error", "Failed to create alert");
    }

    public boolean deleteAlert(int id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM price_alerts WHERE id = ?")) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete alert #%d", id);
            return false;
        }
    }
}
