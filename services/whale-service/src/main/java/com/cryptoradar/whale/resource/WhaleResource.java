package com.cryptoradar.whale.resource;

import com.cryptoradar.whale.model.WhaleAnalytics;
import com.cryptoradar.whale.model.WhaleFlowSummary;
import com.cryptoradar.whale.model.WhaleMarketOverview;
import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.binance.BinanceTradeStreamProvider;
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

@Path("/api/whales")
@Produces(MediaType.APPLICATION_JSON)
public class WhaleResource {

    private final WhaleFlowService flowService;
    private final WhaleAnalyticsService analyticsService;
    private final BinanceTradeStreamProvider binanceProvider;

    public WhaleResource(WhaleFlowService flowService,
                         WhaleAnalyticsService analyticsService,
                         BinanceTradeStreamProvider binanceProvider) {
        this.flowService = flowService;
        this.analyticsService = analyticsService;
        this.binanceProvider = binanceProvider;
    }

    @GET
    @Path("/transactions")
    public List<WhaleTransaction> getTransactions(
            @QueryParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        if (symbol != null && !symbol.isBlank()) {
            return flowService.getRecentTransactions(symbol.toUpperCase(), limit);
        }
        return flowService.getAllRecentTransactions(limit);
    }

    @GET
    @Path("/flow/{symbol}")
    public List<WhaleFlowSummary> getFlowSummary(
            @PathParam("symbol") String symbol,
            @QueryParam("window") @DefaultValue("1h") String window) {
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

    @GET
    @Path("/providers")
    public List<Map<String, Object>> getProviders() {
        return List.of(
                Map.of(
                        "name", "binance",
                        "type", "websocket",
                        "status", binanceProvider.isConnected() ? "connected" : "disconnected",
                        "symbols", analyticsService.getTopSymbols(),
                        "updatedAt", Instant.now().toString()
                )
        );
    }
}
