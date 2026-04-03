package com.cryptoradar.analytics.resource;

import com.cryptoradar.analytics.model.MacroOverview;
import com.cryptoradar.analytics.model.MarketAnalysis;
import com.cryptoradar.analytics.model.MarketOverview;
import com.cryptoradar.analytics.model.TechnicalIndicators;
import com.cryptoradar.analytics.service.AnalyticsService;
import com.cryptoradar.analytics.service.MacroService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
public class AnalyticsResource {

    private final AnalyticsService analyticsService;
    private final MacroService macroService;

    public AnalyticsResource(AnalyticsService analyticsService, MacroService macroService) {
        this.analyticsService = analyticsService;
        this.macroService = macroService;
    }

    @GET
    @Path("/{symbol}")
    public MarketAnalysis getAnalysis(@PathParam("symbol") String symbol) {
        return analyticsService.getAnalysis(symbol.toUpperCase());
    }

    @GET
    @Path("/{symbol}/indicators")
    public TechnicalIndicators getIndicators(@PathParam("symbol") String symbol) {
        MarketAnalysis analysis = analyticsService.getAnalysis(symbol.toUpperCase());
        return analysis.getTechnicalIndicators();
    }

    @GET
    @Path("/market-overview")
    public MarketOverview getMarketOverview() {
        return analyticsService.getMarketOverview();
    }

    @GET
    @Path("/macro")
    public MacroOverview getMacroOverview() {
        return macroService.getMacroOverview();
    }
}
