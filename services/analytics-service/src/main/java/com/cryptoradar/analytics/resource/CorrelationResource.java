package com.cryptoradar.analytics.resource;

import com.cryptoradar.analytics.model.CorrelationMatrix;
import com.cryptoradar.analytics.model.VolatilityMetric;
import com.cryptoradar.analytics.service.CorrelationService;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
public class CorrelationResource {

    private final CorrelationService correlationService;

    public CorrelationResource(CorrelationService correlationService) {
        this.correlationService = correlationService;
    }

    @GET
    @Path("/correlation")
    public CorrelationMatrix getCorrelationMatrix(
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("days") @DefaultValue("30") int days) {
        int clampedDays = Math.max(7, Math.min(days, 365));
        return correlationService.getCorrelationMatrix(interval, clampedDays);
    }

    @GET
    @Path("/volatility")
    public List<VolatilityMetric> getVolatility() {
        return correlationService.getVolatility();
    }
}
