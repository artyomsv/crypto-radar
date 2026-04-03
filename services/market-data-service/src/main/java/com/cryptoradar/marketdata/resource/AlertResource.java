package com.cryptoradar.marketdata.resource;

import com.cryptoradar.marketdata.service.AlertService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Path("/api/alerts")
@Produces(MediaType.APPLICATION_JSON)
public class AlertResource {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]+USDT");
    private static final Set<String> VALID_CONDITIONS = Set.of("ABOVE", "BELOW");

    @Inject
    AlertService alertService;

    @GET
    public Response getAlerts() {
        return Response.ok(alertService.getAlerts()).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAlert(Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        String condition = (String) body.get("condition");
        Object targetPriceObj = body.get("targetPrice");
        String note = (String) body.get("note");

        if (symbol == null || !SYMBOL_PATTERN.matcher(symbol.toUpperCase()).matches()) {
            return Response.status(400).entity(Map.of("error", "Invalid symbol")).build();
        }
        if (condition == null || !VALID_CONDITIONS.contains(condition.toUpperCase())) {
            return Response.status(400).entity(Map.of("error", "Condition must be ABOVE or BELOW")).build();
        }
        if (targetPriceObj == null) {
            return Response.status(400).entity(Map.of("error", "targetPrice is required")).build();
        }

        double targetPrice = ((Number) targetPriceObj).doubleValue();
        if (targetPrice <= 0) {
            return Response.status(400).entity(Map.of("error", "targetPrice must be positive")).build();
        }

        Map<String, Object> result = alertService.createAlert(
                symbol.toUpperCase(), condition.toUpperCase(), targetPrice, note);
        if (result.containsKey("error")) {
            return Response.status(500).entity(result).build();
        }
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteAlert(@PathParam("id") int id) {
        boolean deleted = alertService.deleteAlert(id);
        if (!deleted) {
            return Response.status(404).entity(Map.of("error", "Alert not found")).build();
        }
        return Response.ok(Map.of("id", id, "deleted", true)).build();
    }
}
