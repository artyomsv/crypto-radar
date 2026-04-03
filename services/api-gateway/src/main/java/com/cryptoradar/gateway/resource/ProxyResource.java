package com.cryptoradar.gateway.resource;

import com.cryptoradar.gateway.client.ServiceClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ProxyResource {

    @Inject
    ServiceClient serviceClient;

    // --- Market Data proxies ---

    @GET
    @Path("/market/prices")
    public Response getMarketPrices() {
        String result = serviceClient.getRaw(serviceClient.getMarketDataUrl() + "/api/market/prices");
        return proxyResponse(result);
    }

    @GET
    @Path("/market/candles/{symbol}")
    public Response getCandles(
            @PathParam("symbol") String symbol,
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        String url = serviceClient.getMarketDataUrl() + "/api/market/candles/" + symbol
                + "?interval=" + interval + "&limit=" + limit;
        String result = serviceClient.getRaw(url);
        return proxyResponse(result);
    }

    // --- Crypto Config proxies ---

    @GET
    @Path("/market/config/backfill")
    public Response getBackfillConfig() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getMarketDataUrl() + "/api/market/config/backfill"));
    }

    @PUT
    @Path("/market/config/backfill/{interval}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateBackfillConfig(@PathParam("interval") String interval, String body) {
        return proxyPut(serviceClient.getMarketDataUrl() + "/api/market/config/backfill/" + interval, body);
    }

    @GET
    @Path("/market/config/cryptos")
    public Response getConfigCryptos() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getMarketDataUrl() + "/api/market/config/cryptos"));
    }

    @POST
    @Path("/market/config/cryptos")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addCrypto(String body) {
        return proxyPost(serviceClient.getMarketDataUrl() + "/api/market/config/cryptos", body);
    }

    @PUT
    @Path("/market/config/cryptos/{symbol}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response toggleCrypto(@PathParam("symbol") String symbol, String body) {
        return proxyPut(serviceClient.getMarketDataUrl() + "/api/market/config/cryptos/" + symbol, body);
    }

    @DELETE
    @Path("/market/config/cryptos/{symbol}")
    public Response removeCrypto(@PathParam("symbol") String symbol,
                                  @QueryParam("deleteData") @DefaultValue("false") boolean deleteData) {
        return proxyDelete(serviceClient.getMarketDataUrl() + "/api/market/config/cryptos/" + symbol + "?deleteData=" + deleteData);
    }

    @GET
    @Path("/market/config/search")
    public Response searchSymbols(@QueryParam("q") String q) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getMarketDataUrl() + "/api/market/config/search?q=" + q));
    }

    // --- News proxies ---

    @GET
    @Path("/news/latest")
    public Response getLatestNews(@QueryParam("limit") @DefaultValue("20") int limit) {
        String result = serviceClient.getRaw(serviceClient.getNewsServiceUrl() + "/api/news/latest?limit=" + limit);
        return proxyResponse(result);
    }

    @GET
    @Path("/news/fetch")
    public Response fetchArticle(@QueryParam("url") String url) {
        String result = serviceClient.getRaw(
                serviceClient.getNewsServiceUrl() + "/api/news/fetch?url=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8));
        return proxyResponse(result);
    }

    @GET
    @Path("/news/sentiment/{symbol}")
    public Response getSentiment(@PathParam("symbol") String symbol) {
        String result = serviceClient.getRaw(serviceClient.getNewsServiceUrl() + "/api/news/sentiment/" + symbol);
        return proxyResponse(result);
    }

    // --- Analytics proxies ---

    @GET
    @Path("/analytics/{symbol}")
    public Response getAnalysis(@PathParam("symbol") String symbol) {
        String result = serviceClient.getRaw(serviceClient.getAnalyticsServiceUrl() + "/api/analytics/" + symbol);
        return proxyResponse(result);
    }

    @GET
    @Path("/analytics/market-overview")
    public Response getMarketOverview() {
        String result = serviceClient.getRaw(serviceClient.getAnalyticsServiceUrl() + "/api/analytics/market-overview");
        return proxyResponse(result);
    }

    // --- Whale proxies ---

    @GET
    @Path("/whales/transactions")
    public Response getWhaleTransactions(
            @QueryParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        String url = serviceClient.getWhaleServiceUrl() + "/api/whales/transactions?limit=" + limit;
        if (symbol != null && !symbol.isEmpty()) {
            url += "&symbol=" + symbol;
        }
        String result = serviceClient.getRaw(url);
        return proxyResponse(result);
    }

    @GET
    @Path("/whales/analytics")
    public Response getWhaleAnalytics() {
        String result = serviceClient.getRaw(serviceClient.getWhaleServiceUrl() + "/api/whales/analytics");
        return proxyResponse(result);
    }

    @GET
    @Path("/whales/flow/{symbol}")
    public Response getWhaleFlow(
            @PathParam("symbol") String symbol,
            @QueryParam("window") @DefaultValue("24h") String window) {
        String result = serviceClient.getRaw(
                serviceClient.getWhaleServiceUrl() + "/api/whales/flow/" + symbol + "?window=" + window);
        return proxyResponse(result);
    }

    @GET
    @Path("/whales/summary")
    public Response getWhaleSummary() {
        String result = serviceClient.getRaw(serviceClient.getWhaleServiceUrl() + "/api/whales/summary");
        return proxyResponse(result);
    }

    // --- Derivatives proxies ---

    @GET
    @Path("/derivatives/overview")
    public Response getDerivativesOverview() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getDerivativesServiceUrl() + "/api/derivatives/overview"));
    }

    @GET
    @Path("/derivatives/{symbol}")
    public Response getSymbolDerivatives(@PathParam("symbol") String symbol) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getDerivativesServiceUrl() + "/api/derivatives/" + symbol));
    }

    @GET
    @Path("/derivatives/funding-rates")
    public Response getFundingRates() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getDerivativesServiceUrl() + "/api/derivatives/funding-rates"));
    }

    @GET
    @Path("/derivatives/liquidations")
    public Response getLiquidations(@QueryParam("limit") @DefaultValue("50") int limit) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getDerivativesServiceUrl() + "/api/derivatives/liquidations?limit=" + Math.min(limit, 500)));
    }

    @GET
    @Path("/derivatives/liquidation-map/{symbol}")
    public Response getLiquidationMap(@PathParam("symbol") String symbol) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getDerivativesServiceUrl() + "/api/derivatives/liquidation-map/" + symbol));
    }

    private Response proxyResponse(String body) {
        if (body == null) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Upstream service unavailable\"}")
                    .build();
        }
        return Response.ok(body).build();
    }

    private Response proxyPost(String url, String body) {
        String result = serviceClient.postRaw(url, body);
        return proxyResponse(result);
    }

    private Response proxyPut(String url, String body) {
        String result = serviceClient.putRaw(url, body);
        return proxyResponse(result);
    }

    private Response proxyDelete(String url) {
        String result = serviceClient.deleteRaw(url);
        return proxyResponse(result);
    }
}
