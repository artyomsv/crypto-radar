package com.cryptoradar.whale.resource;

import com.cryptoradar.whale.model.WhaleAnalytics;
import com.cryptoradar.whale.model.WhaleFlowSummary;
import com.cryptoradar.whale.model.WhaleMarketOverview;
import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.alert.WhaleAlertProvider;
import com.cryptoradar.whale.provider.exchange.TradeStreamManager;
import com.cryptoradar.whale.service.WhaleAnalyticsService;
import com.cryptoradar.whale.service.WhaleFlowService;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/api/whales")
@Produces(MediaType.APPLICATION_JSON)
public class WhaleResource {

    private static final Set<String> VALID_WINDOWS = Set.of("1h", "4h", "24h");
    private static final Set<String> VALID_PERIODS = Set.of("1d", "1w", "2w", "1m", "3m", "6m", "1y");

    private final WhaleFlowService flowService;
    private final WhaleAnalyticsService analyticsService;
    private final TradeStreamManager tradeStreamManager;
    private final WhaleAlertProvider whaleAlertProvider;

    public WhaleResource(WhaleFlowService flowService,
                         WhaleAnalyticsService analyticsService,
                         TradeStreamManager tradeStreamManager,
                         WhaleAlertProvider whaleAlertProvider) {
        this.flowService = flowService;
        this.analyticsService = analyticsService;
        this.tradeStreamManager = tradeStreamManager;
        this.whaleAlertProvider = whaleAlertProvider;
    }

    @GET
    @Path("/transactions")
    public List<WhaleTransaction> getTransactions(
            @QueryParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("period") @DefaultValue("1d") String period) {
        int clampedLimit = Math.min(Math.max(1, limit), 500);
        if (!VALID_PERIODS.contains(period)) {
            period = "1d";
        }
        if (symbol != null && !symbol.isBlank()) {
            return flowService.getRecentTransactions(symbol.toUpperCase(), clampedLimit, period);
        }
        return flowService.getAllRecentTransactions(clampedLimit, period);
    }

    @GET
    @Path("/flow/{symbol}")
    public List<WhaleFlowSummary> getFlowSummary(
            @PathParam("symbol") String symbol,
            @QueryParam("window") @DefaultValue("1h") String window) {
        if (!VALID_WINDOWS.contains(window)) {
            return List.of();
        }
        return flowService.getFlowSummary(symbol.toUpperCase(), window);
    }

    @GET
    @Path("/analytics")
    public WhaleMarketOverview getMarketOverview() {
        return analyticsService.getMarketOverview();
    }

    @GET
    @Path("/analytics/{symbol}")
    public WhaleAnalytics getSymbolAnalytics(@PathParam("symbol") String symbol) {
        return analyticsService.getAnalytics(symbol.toUpperCase());
    }

    @GET
    @Path("/summary")
    public Map<String, Object> getSummary() {
        WhaleMarketOverview overview = analyticsService.getMarketOverview();
        return Map.of(
                "timestamp", overview.getTimestamp().toString(),
                "totalVolume24h", overview.getTotalWhaleVolume24h(),
                "buyVolume24h", overview.getTotalBuyVolume24h(),
                "sellVolume24h", overview.getTotalSellVolume24h(),
                "pressure", overview.getOverallPressure(),
                "pressureLabel", overview.getOverallPressureLabel(),
                "activeSymbols", overview.getActiveSymbolCount(),
                "tradeCount24h", overview.getTotalTradeCount24h()
        );
    }

    private static final Set<String> VALID_DIST_WINDOWS = Set.of("1h", "2h", "4h", "8h", "12h", "24h");

    @GET
    @Path("/distribution")
    public Map<String, Object> getDistribution(@QueryParam("window") @DefaultValue("1h") String window) {
        if (!VALID_DIST_WINDOWS.contains(window)) {
            return Map.of("error", "Invalid window. Valid: " + VALID_DIST_WINDOWS);
        }
        return flowService.getDistribution(window);
    }

    @GET
    @Path("/providers")
    public List<Map<String, Object>> getProviders() {
        List<Map<String, Object>> providers = new java.util.ArrayList<>(tradeStreamManager.getProviderStatus());
        providers.add(Map.of(
                "name", "Whale Alert",
                "type", "rest",
                "status", whaleAlertProvider.isEnabled() ? "active" : "no-api-key",
                "description", "Cross-chain whale transactions (BTC, ETH, XRP, SOL, etc.)",
                "updatedAt", Instant.now().toString()
        ));
        return providers;
    }
}
