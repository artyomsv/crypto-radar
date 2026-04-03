package com.cryptoradar.gateway.resource;

import com.cryptoradar.gateway.dto.CryptoDetail;
import com.cryptoradar.gateway.dto.DashboardData;
import com.cryptoradar.gateway.service.AggregationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/dashboard")
@Produces(MediaType.APPLICATION_JSON)
public class DashboardResource {

    @Inject
    AggregationService aggregationService;

    @GET
    public DashboardData getDashboard() {
        return aggregationService.getDashboard();
    }

    @GET
    @Path("/{symbol}")
    public CryptoDetail getCryptoDetail(@PathParam("symbol") String symbol) {
        return aggregationService.getCryptoDetail(symbol);
    }
}
