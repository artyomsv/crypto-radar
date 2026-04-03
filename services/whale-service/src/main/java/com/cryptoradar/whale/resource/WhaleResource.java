package com.cryptoradar.whale.resource;

import com.cryptoradar.whale.model.WhaleAnalytics;
import com.cryptoradar.whale.model.WhaleFlowSummary;
import com.cryptoradar.whale.model.WhaleMarketOverview;
import com.cryptoradar.whale.model.WhaleTransaction;
import com.cryptoradar.whale.provider.alert.WhaleAlertProvider;
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
    private final WhaleAlertProvider whaleAlertProvider;

    public WhaleResource(WhaleFlowService flowService,
                         WhaleAnalyticsService analyticsService,
                         BinanceTradeStreamProvider binanceProvider,
                         WhaleAlertProvider whaleAlertProvider) {
        this.flowService = flowService;
        this.analyticsService = analyticsService;
        this.binanceProvider = binanceProvider;
        this.whaleAlertProvider = whaleAlertProvider;
    }

    @GET
    @Path("/transactions")
    public List<WhaleTransaction> getTransactions(
            @QueryParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("period") @DefaultValue("1d") String period) {
        if (symbol != null && !symbol.isBlank()) {
            return flowService.getRecentTransactions(symbol.toUpperCase(), limit, period);
        }
        return flowService.getAllRecentTransactions(limit, period);
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
                        "name", "Binance Trade Stream",
                        "type", "websocket",
                        "status", binanceProvider.isConnected() ? "active" : "disconnected",
                        "description", "Real-time aggTrade stream for 10 symbols",
                        "symbols", analyticsService.getTopSymbols(),
                        "updatedAt", Instant.now().toString()
                ),
                Map.of(
                        "name", "Whale Alert",
                        "type", "rest",
                        "status", whaleAlertProvider.isEnabled() ? "active" : "no-api-key",
                        "description", "Cross-chain whale transactions (BTC, ETH, XRP, SOL, etc.)",
                        "updatedAt", Instant.now().toString()
                )
        );
    }
}
