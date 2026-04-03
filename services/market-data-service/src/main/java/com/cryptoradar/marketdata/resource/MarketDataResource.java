package com.cryptoradar.marketdata.resource;

import com.cryptoradar.marketdata.model.Candle;
import com.cryptoradar.marketdata.model.CryptoAsset;
import com.cryptoradar.marketdata.model.PriceSnapshot;
import com.cryptoradar.marketdata.service.MarketDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/market")
@Produces(MediaType.APPLICATION_JSON)
public class MarketDataResource {

    @Inject
    MarketDataService marketDataService;

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
}
