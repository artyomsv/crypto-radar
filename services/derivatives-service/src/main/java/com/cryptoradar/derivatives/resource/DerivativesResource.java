package com.cryptoradar.derivatives.resource;

import com.cryptoradar.derivatives.model.DerivativesOverview;
import com.cryptoradar.derivatives.model.FundingRate;
import com.cryptoradar.derivatives.model.Liquidation;
import com.cryptoradar.derivatives.model.SymbolDerivatives;
import com.cryptoradar.derivatives.service.DerivativesService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Path("/api/derivatives")
@Produces(MediaType.APPLICATION_JSON)
public class DerivativesResource {

    private static final Logger LOG = Logger.getLogger(DerivativesResource.class);

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]+USDT");

    @Inject
    DerivativesService derivativesService;

    @GET
    @Path("/overview")
    public DerivativesOverview getOverview() {
        return derivativesService.getOverview();
    }

    @GET
    @Path("/{symbol}")
    public Response getSymbolDerivatives(@PathParam("symbol") String symbol) {
        String normalized = symbol.toUpperCase();
        if (!SYMBOL_PATTERN.matcher(normalized).matches()) {
            return Response.status(400)
                    .entity(Map.of("error", "Invalid symbol format"))
                    .build();
        }
        SymbolDerivatives data = derivativesService.getSymbolDerivatives(normalized);
        return Response.ok(data).build();
    }

    @GET
    @Path("/funding-rates")
    public List<FundingRate> getFundingRates() {
        return derivativesService.getAllFundingRates();
    }

    @GET
    @Path("/liquidations")
    public List<Liquidation> getRecentLiquidations(
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return derivativesService.getRecentLiquidations(limit);
    }
}
