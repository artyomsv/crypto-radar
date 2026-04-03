package com.cryptoradar.gateway.resource;

import com.cryptoradar.gateway.client.ServiceClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
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

    private Response proxyResponse(String body) {
        if (body == null) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Upstream service unavailable\"}")
                    .build();
        }
        return Response.ok(body).build();
    }
}
