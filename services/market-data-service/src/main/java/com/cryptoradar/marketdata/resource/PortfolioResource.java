package com.cryptoradar.marketdata.resource;

import com.cryptoradar.marketdata.service.PortfolioService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Path("/api/portfolio")
@Produces(MediaType.APPLICATION_JSON)
public class PortfolioResource {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]+USDT");
    private static final Set<String> VALID_SIDES = Set.of("LONG", "SHORT");

    @Inject
    PortfolioService portfolioService;

    @GET
    public Response getPositions() {
        return Response.ok(portfolioService.getPositions()).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addPosition(Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        Object entryPriceObj = body.get("entryPrice");
        Object quantityObj = body.get("quantity");
        String side = (String) body.get("side");
        String note = (String) body.get("note");

        if (symbol == null || !SYMBOL_PATTERN.matcher(symbol.toUpperCase()).matches()) {
            return Response.status(400).entity(Map.of("error", "Invalid symbol")).build();
        }
        if (entryPriceObj == null) {
            return Response.status(400).entity(Map.of("error", "entryPrice is required")).build();
        }
        if (quantityObj == null) {
            return Response.status(400).entity(Map.of("error", "quantity is required")).build();
        }

        double entryPrice = ((Number) entryPriceObj).doubleValue();
        double quantity = ((Number) quantityObj).doubleValue();

        if (entryPrice <= 0) {
            return Response.status(400).entity(Map.of("error", "entryPrice must be positive")).build();
        }
        if (quantity <= 0) {
            return Response.status(400).entity(Map.of("error", "quantity must be positive")).build();
        }

        String validSide = "LONG";
        if (side != null && VALID_SIDES.contains(side.toUpperCase())) {
            validSide = side.toUpperCase();
        }

        Map<String, Object> result = portfolioService.addPosition(
                symbol.toUpperCase(), entryPrice, quantity, validSide, note);
        if (result.containsKey("error")) {
            return Response.status(500).entity(result).build();
        }
        return Response.ok(result).build();
    }

    @PUT
    @Path("/{id}/close")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response closePosition(@PathParam("id") int id, Map<String, Object> body) {
        Object closePriceObj = body.get("closePrice");
        if (closePriceObj == null) {
            return Response.status(400).entity(Map.of("error", "closePrice is required")).build();
        }
        double closePrice = ((Number) closePriceObj).doubleValue();
        if (closePrice <= 0) {
            return Response.status(400).entity(Map.of("error", "closePrice must be positive")).build();
        }

        Map<String, Object> result = portfolioService.closePosition(id, closePrice);
        if (result.containsKey("error")) {
            return Response.status(404).entity(result).build();
        }
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deletePosition(@PathParam("id") int id) {
        boolean deleted = portfolioService.deletePosition(id);
        if (!deleted) {
            return Response.status(404).entity(Map.of("error", "Position not found")).build();
        }
        return Response.ok(Map.of("id", id, "deleted", true)).build();
    }
}
