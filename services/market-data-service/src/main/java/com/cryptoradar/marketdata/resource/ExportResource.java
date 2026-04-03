package com.cryptoradar.marketdata.resource;

import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.regex.Pattern;

@Path("/api/market/export")
public class ExportResource {

    private static final Logger LOG = Logger.getLogger(ExportResource.class);
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]+USDT");
    private static final Set<String> VALID_INTERVALS = Set.of(
            "1m", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "12h", "1d", "1w"
    );
    private static final int MAX_ROWS = 100_000;

    @Inject
    AgroalDataSource dataSource;

    @GET
    @Path("/{symbol}")
    @Produces("text/csv")
    public Response exportCandles(
            @PathParam("symbol") String symbol,
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("from") String fromDate,
            @QueryParam("to") String toDate) {

        String normalizedSymbol = symbol.toUpperCase();
        if (!SYMBOL_PATTERN.matcher(normalizedSymbol).matches()) {
            return Response.status(400).entity("Invalid symbol format").build();
        }
        if (!VALID_INTERVALS.contains(interval)) {
            return Response.status(400).entity("Invalid interval").build();
        }

        Instant fromInstant = parseDate(fromDate, Instant.EPOCH);
        Instant toInstant = parseDate(toDate, Instant.now());

        StringBuilder csv = new StringBuilder();
        csv.append("time,open,high,low,close,volume\n");

        String sql = "SELECT time, open, high, low, close, volume FROM candles " +
                "WHERE symbol = ? AND interval = ? AND time >= ? AND time <= ? " +
                "ORDER BY time ASC LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, normalizedSymbol);
            stmt.setString(2, interval);
            stmt.setTimestamp(3, Timestamp.from(fromInstant));
            stmt.setTimestamp(4, Timestamp.from(toInstant));
            stmt.setInt(5, MAX_ROWS);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    csv.append(rs.getTimestamp("time").toInstant())
                            .append(',')
                            .append(rs.getDouble("open"))
                            .append(',')
                            .append(rs.getDouble("high"))
                            .append(',')
                            .append(rs.getDouble("low"))
                            .append(',')
                            .append(rs.getDouble("close"))
                            .append(',')
                            .append(rs.getDouble("volume"))
                            .append('\n');
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to export candles for %s", normalizedSymbol);
            return Response.status(500).entity("Export failed").build();
        }

        String filename = normalizedSymbol + "_" + interval + "_candles.csv";
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    private Instant parseDate(String dateStr, Instant fallback) {
        if (dateStr == null || dateStr.isBlank()) return fallback;
        try {
            return LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return fallback;
        }
    }
}
