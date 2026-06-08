package com.cryptoradar.execution.resource;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only analytics endpoints for the execution funnel and per-cell
 * strategy performance. Backs the dashboard and answers "why was this
 * signal blocked?" / "what's actually working?".
 *
 * <p>{@code GET /api/execution/analytics/funnel?hours=24} returns the per-gate
 * rejection counts over the last N hours. The four gates that opt into
 * coalesced events are visible here: ALIGNMENT_FLOOR, SYMBOL_PERF,
 * CONFLUENCE, DEDUP. DAILY_HALT also shows when triggered.
 *
 * <p>{@code GET /api/execution/analytics/strategy-pnl?days=14} returns
 * per-(symbol, direction, strategy) realized R, sample size, and a normal-
 * approximation 95% CI. Used to decide whether to keep or kill a cell.
 *
 * <p>Both endpoints are pure-read native queries against the shared DB —
 * no auth (internal observability only).
 */
@Path("/api/execution/analytics")
@Produces(MediaType.APPLICATION_JSON)
public class ObservabilityResource {

    private static final Logger LOG = Logger.getLogger(ObservabilityResource.class);

    @Inject EntityManager entityManager;

    @GET
    @Path("/funnel")
    public Response funnel(@QueryParam("hours") Integer hours) {
        int windowHours = hours == null || hours <= 0 ? 24 : Math.min(hours, 720);
        try {
            List<Object[]> rows = listOf(entityManager.createNativeQuery(
                            "SELECT event_type::text, COUNT(*) AS n, "
                            + "       array_agg(DISTINCT (metadata->>'symbol')) AS symbols "
                            + "FROM execution_events "
                            + "WHERE created_at >= NOW() - (:hours || ' hours')::INTERVAL "
                            + "  AND event_type::text LIKE 'SIGNAL_BLOCKED_%' "
                            + "GROUP BY 1 ORDER BY 2 DESC")
                    .setParameter("hours", windowHours)
                    .getResultList());
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("eventType", row[0]);
                entry.put("count", ((Number) row[1]).longValue());
                entry.put("symbols", row[2]);
                result.add(entry);
            }
            return Response.ok(Map.of("windowHours", windowHours, "gates", result)).build();
        } catch (RuntimeException e) {
            LOG.warnf(e, "funnel query failed");
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/strategy-pnl")
    public Response strategyPnl(@QueryParam("days") Integer days) {
        int windowDays = days == null || days <= 0 ? 14 : Math.min(days, 90);
        try {
            List<Object[]> rows = listOf(entityManager.createNativeQuery(
                            "WITH closed AS ( "
                            + "  SELECT symbol, direction, strategy, realized_r_multiple "
                            + "  FROM signal_outcomes "
                            + "  WHERE fired_at >= NOW() - (:days || ' days')::INTERVAL "
                            + "    AND final_exit_reason IS NOT NULL "
                            + "    AND realized_r_multiple IS NOT NULL "
                            + "), cells AS ( "
                            + "  SELECT symbol, direction, strategy, "
                            + "         COUNT(*) AS n, "
                            + "         AVG(realized_r_multiple) AS mean_r, "
                            + "         STDDEV_SAMP(realized_r_multiple) AS std_r, "
                            + "         SUM(realized_r_multiple) AS total_r "
                            + "  FROM closed GROUP BY 1,2,3 "
                            + ") "
                            + "SELECT symbol, direction, strategy, n, "
                            + "       ROUND(mean_r::numeric, 3) AS mean_r, "
                            + "       ROUND(std_r::numeric, 3) AS std_r, "
                            + "       ROUND(total_r::numeric, 2) AS total_r, "
                            + "       ROUND((mean_r - 1.96 * std_r / NULLIF(SQRT(n),0))::numeric, 3) AS ci_low, "
                            + "       ROUND((mean_r + 1.96 * std_r / NULLIF(SQRT(n),0))::numeric, 3) AS ci_high "
                            + "FROM cells ORDER BY total_r DESC NULLS LAST")
                    .setParameter("days", windowDays)
                    .getResultList());
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("symbol", row[0]);
                entry.put("direction", row[1]);
                entry.put("strategy", row[2]);
                entry.put("n", ((Number) row[3]).intValue());
                entry.put("meanR", toDouble(row[4]));
                entry.put("stdR", toDouble(row[5]));
                entry.put("totalR", toDouble(row[6]));
                entry.put("ciLow", toDouble(row[7]));
                entry.put("ciHigh", toDouble(row[8]));
                entry.put("verdict", verdictFor(((Number) row[3]).intValue(), toDouble(row[7]), toDouble(row[8])));
                result.add(entry);
            }
            return Response.ok(Map.of("windowDays", windowDays, "cells", result)).build();
        } catch (RuntimeException e) {
            LOG.warnf(e, "strategy-pnl query failed");
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    private static String verdictFor(int n, Double ciLow, Double ciHigh) {
        if (n < 30) return "INSUFFICIENT_N";
        if (ciLow != null && ciLow > 0) return "EDGE_POSITIVE";
        if (ciHigh != null && ciHigh < 0) return "EDGE_NEGATIVE";
        return "ZERO_INDISTINGUISHABLE";
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof BigDecimal bd) return bd.doubleValue();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> listOf(Object result) {
        return (List<Object[]>) result;
    }
}
