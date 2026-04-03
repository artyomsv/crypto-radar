package com.cryptoradar.whale.resource;

import io.agroal.api.AgroalDataSource;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Path("/api/whales/export")
public class WhaleExportResource {

    private static final Logger LOG = Logger.getLogger(WhaleExportResource.class);
    private static final int MAX_ROWS = 50_000;
    private static final Set<String> VALID_PERIODS = Set.of("1d", "1w", "2w", "1m", "3m", "6m", "1y");

    private static final Map<String, Duration> PERIOD_DURATIONS = Map.of(
            "1d", Duration.ofDays(1),
            "1w", Duration.ofDays(7),
            "2w", Duration.ofDays(14),
            "1m", Duration.ofDays(30),
            "3m", Duration.ofDays(90),
            "6m", Duration.ofDays(180),
            "1y", Duration.ofDays(365)
    );

    private final AgroalDataSource dataSource;

    public WhaleExportResource(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GET
    @Produces("text/csv")
    public Response exportWhaleTransactions(
            @QueryParam("period") @DefaultValue("1d") String period) {

        if (!VALID_PERIODS.contains(period)) {
            return Response.status(400).entity("Invalid period").build();
        }

        Duration duration = PERIOD_DURATIONS.get(period);
        Instant since = Instant.now().minus(duration);

        StringBuilder csv = new StringBuilder();
        csv.append("time,symbol,side,price,quantity,value_usd,source\n");

        String sql = "SELECT time, symbol, side, price, quantity, value_usd, source " +
                "FROM whale_transactions WHERE time >= ? ORDER BY time DESC LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(since));
            stmt.setInt(2, MAX_ROWS);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    csv.append(rs.getTimestamp("time").toInstant())
                            .append(',')
                            .append(rs.getString("symbol"))
                            .append(',')
                            .append(rs.getString("side"))
                            .append(',')
                            .append(rs.getDouble("price"))
                            .append(',')
                            .append(rs.getDouble("quantity"))
                            .append(',')
                            .append(rs.getDouble("value_usd"))
                            .append(',')
                            .append(rs.getString("source"))
                            .append('\n');
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to export whale transactions");
            return Response.status(500).entity("Export failed").build();
        }

        String filename = "whale_transactions_" + period + ".csv";
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }
}
