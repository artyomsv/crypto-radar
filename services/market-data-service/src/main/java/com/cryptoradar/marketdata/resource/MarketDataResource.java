package com.cryptoradar.marketdata.resource;

import com.cryptoradar.marketdata.client.BinanceClient;
import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.CryptoAsset;
import com.cryptoradar.marketdata.model.PriceSnapshot;
import com.cryptoradar.marketdata.service.BackfillService;
import com.cryptoradar.marketdata.service.MarketDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/api/market")
@Produces(MediaType.APPLICATION_JSON)
public class MarketDataResource {

    @Inject
    MarketDataService marketDataService;

    @Inject
    BackfillService backfillService;

    @Inject
    BinanceClient binanceClient;

    @GET
    @Path("/prices")
    public List<PriceSnapshot> getLatestPrices() {
        return marketDataService.getLatestPrices();
    }

    @GET
    @Path("/candles/{symbol}")
    public List<Candle> getCandles(
            @PathParam("symbol") String symbol,
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return marketDataService.getCandles(symbol, interval, limit);
    }

    @GET
    @Path("/assets")
    public List<CryptoAsset> getAssets() {
        return marketDataService.getAssets();
    }

    @GET
    @Path("/candles/{symbol}/latest")
    public Candle getLatestCandle(@PathParam("symbol") String symbol) {
        List<Candle> candles = marketDataService.getCandles(symbol, "1h", 1);
        return candles.isEmpty() ? null : candles.getFirst();
    }

    /** Trigger manual backfill for a specific symbol and interval */
    @POST
    @Path("/backfill/{symbol}/{interval}")
    public BackfillService.BackfillResult triggerBackfill(
            @PathParam("symbol") String symbol,
            @PathParam("interval") String interval) {
        return backfillService.backfill(symbol.toUpperCase(), interval);
    }

    /** Trigger backfill for all symbols on a specific interval */
    @POST
    @Path("/backfill/all/{interval}")
    public List<BackfillService.BackfillResult> triggerBackfillAll(
            @PathParam("interval") String interval) {
        List<BackfillService.BackfillResult> results = new ArrayList<>();
        for (String symbol : binanceClient.getTrackedSymbols()) {
            results.add(backfillService.backfill(symbol, interval));
        }
        return results;
    }

    /** Get available intervals */
    @GET
    @Path("/intervals")
    public Map<String, Object> getIntervals() {
        return Map.of(
                "intervals", List.of("1m", "5m", "15m", "1h", "4h", "1d"),
                "default", "1h"
        );
    }
}
