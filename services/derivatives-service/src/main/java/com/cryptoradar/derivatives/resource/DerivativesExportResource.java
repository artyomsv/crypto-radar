package com.cryptoradar.derivatives.resource;

import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Path("/api/derivatives/export")
public class DerivativesExportResource {

    private static final Logger LOG = Logger.getLogger(DerivativesExportResource.class);
    private static final int MAX_ROWS = 50_000;

    @Inject
    AgroalDataSource dataSource;

    @GET
    @Path("/liquidations")
    @Produces("text/csv")
    public Response exportLiquidations() {
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);

        StringBuilder csv = new StringBuilder();
        csv.append("time,symbol,side,price,quantity,value_usd,exchange\n");

        String sql = "SELECT time, symbol, side, price, quantity, value_usd, exchange " +
                "FROM liquidations WHERE time >= ? ORDER BY time DESC LIMIT ?";

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
                            .append(rs.getString("exchange") == null ? "" : rs.getString("exchange"))
                            .append('\n');
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to export liquidations");
            return Response.status(500).entity("Export failed").build();
        }

        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"liquidations_7d.csv\"")
                .build();
    }
}
