package com.cryptoradar.gateway.resource;

import com.cryptoradar.gateway.client.ServiceClient;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ProxyResource {

    private static final Logger LOG = Logger.getLogger(ProxyResource.class);

    private static final java.util.regex.Pattern VALID_SYMBOL = java.util.regex.Pattern.compile("[A-Z0-9]{2,20}");

    private final ServiceClient serviceClient;

    public ProxyResource(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    private boolean isInvalidSymbol(String symbol) {
        return symbol == null || !VALID_SYMBOL.matcher(symbol.toUpperCase()).matches();
    }

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

    // --- Order Book Depth proxy ---

    @GET
    @Path("/market/depth/{symbol}")
    public Response getOrderBookDepth(@PathParam("symbol") String symbol) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getMarketDataUrl() + "/api/market/depth/" + symbol));
    }

    // --- Alerts proxies ---

    @GET
    @Path("/alerts")
    public Response getAlerts() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getMarketDataUrl() + "/api/alerts"));
    }

    @POST
    @Path("/alerts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAlert(String body) {
        return proxyPost(serviceClient.getMarketDataUrl() + "/api/alerts", body);
    }

    @DELETE
    @Path("/alerts/{id}")
    public Response deleteAlert(@PathParam("id") int id) {
        return proxyDelete(serviceClient.getMarketDataUrl() + "/api/alerts/" + id);
    }

    // --- Portfolio proxies ---

    @GET
    @Path("/portfolio")
    public Response getPortfolio() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getMarketDataUrl() + "/api/portfolio"));
    }

    @POST
    @Path("/portfolio")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addPortfolioPosition(String body) {
        return proxyPost(serviceClient.getMarketDataUrl() + "/api/portfolio", body);
    }

    @PUT
    @Path("/portfolio/{id}/close")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response closePortfolioPosition(@PathParam("id") int id, String body) {
        return proxyPut(serviceClient.getMarketDataUrl() + "/api/portfolio/" + id + "/close", body);
    }

    @DELETE
    @Path("/portfolio/{id}")
    public Response deletePortfolioPosition(@PathParam("id") int id) {
        return proxyDelete(serviceClient.getMarketDataUrl() + "/api/portfolio/" + id);
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

    @GET
    @Path("/analytics/correlation")
    public Response getCorrelation(
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("days") @DefaultValue("30") int days) {
        String url = serviceClient.getAnalyticsServiceUrl()
                + "/api/analytics/correlation?interval=" + interval + "&days=" + days;
        return proxyResponse(serviceClient.getRaw(url));
    }

    @GET
    @Path("/analytics/volatility")
    public Response getVolatility() {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getAnalyticsServiceUrl() + "/api/analytics/volatility"));
    }

    @GET
    @Path("/analytics/macro")
    public Response getMacroOverview() {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getAnalyticsServiceUrl() + "/api/analytics/macro"));
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

    @GET
    @Path("/whales/distribution")
    public Response getWhaleDistribution(@QueryParam("window") @DefaultValue("1h") String window) {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getWhaleServiceUrl() + "/api/whales/distribution?window=" + window));
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

    // --- Signal config proxies ---

    @GET
    @Path("/signals/config")
    public Response getActiveSignalConfig() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getSignalServiceUrl() + "/api/signals/config"));
    }

    @GET
    @Path("/signals/config/versions")
    public Response listSignalConfigVersions(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        String url = serviceClient.getSignalServiceUrl()
                + "/api/signals/config/versions?limit=" + limit + "&offset=" + offset;
        return proxyResponse(serviceClient.getRaw(url));
    }

    @GET
    @Path("/signals/config/versions/{id}")
    public Response getSignalConfigVersion(@PathParam("id") long id) {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getSignalServiceUrl() + "/api/signals/config/versions/" + id));
    }

    @POST
    @Path("/signals/config/versions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSignalConfigVersion(String body) {
        return propagate(serviceClient.postWithStatus(
                serviceClient.getSignalServiceUrl() + "/api/signals/config/versions", body));
    }

    @POST
    @Path("/signals/config/versions/{id}/activate")
    public Response activateSignalConfigVersion(@PathParam("id") long id) {
        return propagate(serviceClient.postWithStatus(
                serviceClient.getSignalServiceUrl() + "/api/signals/config/versions/" + id + "/activate", ""));
    }

    // --- Signal proxies ---

    @GET
    @Path("/signals/overview")
    public Response getSignalOverview() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getSignalServiceUrl() + "/api/signals/overview"));
    }

    @GET
    @Path("/signals/probability/calibration")
    public Response getProbabilityCalibration() {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getSignalServiceUrl() + "/api/signals/probability/calibration"));
    }

    @GET
    @Path("/signals/metrics")
    public Response getSignalMetrics(@QueryParam("periodDays") @DefaultValue("30") int periodDays) {
        String url = serviceClient.getSignalServiceUrl() + "/api/signals/metrics?periodDays=" + periodDays;
        return proxyResponse(serviceClient.getRaw(url));
    }

    @GET
    @Path("/signals/outcomes")
    public Response getSignalOutcomes(
            @QueryParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        StringBuilder url = new StringBuilder(serviceClient.getSignalServiceUrl());
        url.append("/api/signals/outcomes?limit=").append(limit);
        if (symbol != null && !symbol.isBlank()) {
            url.append("&symbol=").append(symbol.toUpperCase());
        }
        return proxyResponse(serviceClient.getRaw(url.toString()));
    }

    @GET
    @Path("/signals/{symbol}")
    public Response getSignalForSymbol(@PathParam("symbol") String symbol) {
        if (isInvalidSymbol(symbol)) return Response.status(400).build();
        return proxyResponse(serviceClient.getRaw(serviceClient.getSignalServiceUrl() + "/api/signals/" + symbol));
    }

    @GET
    @Path("/signals/{symbol}/raw-data")
    public Response getSignalRawData(@PathParam("symbol") String symbol) {
        if (isInvalidSymbol(symbol)) return Response.status(400).build();
        return proxyResponse(serviceClient.getRaw(serviceClient.getSignalServiceUrl() + "/api/signals/" + symbol + "/raw-data"));
    }

    @POST
    @Path("/signals/{symbol}/ai-analysis")
    public Response requestAiAnalysis(@PathParam("symbol") String symbol) {
        if (isInvalidSymbol(symbol)) return Response.status(400).build();
        return proxyResponse(serviceClient.postRaw(serviceClient.getSignalServiceUrl() + "/api/signals/" + symbol + "/ai-analysis", ""));
    }

    // --- Export proxies (CSV pass-through) ---

    @GET
    @Path("/market/export/{symbol}")
    public Response exportCandles(
            @PathParam("symbol") String symbol,
            @QueryParam("interval") @DefaultValue("1h") String interval,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        String url = serviceClient.getMarketDataUrl() + "/api/market/export/" + symbol
                + "?interval=" + interval;
        if (from != null && !from.isEmpty()) url += "&from=" + from;
        if (to != null && !to.isEmpty()) url += "&to=" + to;
        return proxyCsvResponse(serviceClient.getRaw(url), symbol + "_" + interval + "_candles.csv");
    }

    @GET
    @Path("/whales/export")
    public Response exportWhaleTransactions(
            @QueryParam("period") @DefaultValue("1d") String period) {
        String url = serviceClient.getWhaleServiceUrl() + "/api/whales/export?period=" + period;
        return proxyCsvResponse(serviceClient.getRaw(url), "whale_transactions_" + period + ".csv");
    }

    @GET
    @Path("/derivatives/export/liquidations")
    public Response exportLiquidations() {
        String url = serviceClient.getDerivativesServiceUrl() + "/api/derivatives/export/liquidations";
        return proxyCsvResponse(serviceClient.getRaw(url), "liquidations_7d.csv");
    }

    private Response proxyResponse(String body) {
        if (body == null) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Upstream service unavailable\"}")
                    .build();
        }
        return Response.ok(body).build();
    }

    private Response proxyCsvResponse(String body, String filename) {
        if (body == null) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("Upstream service unavailable")
                    .build();
        }
        return Response.ok(body)
                .header("Content-Type", "text/csv")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
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

    private Response proxyPatch(String url, String body) {
        try {
            String result = serviceClient.patchRaw(url, body);
            return proxyResponse(result);
        } catch (RuntimeException e) {
            LOG.warnf(e, "proxy PATCH failed: %s", url);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"upstream patch failed\"}")
                    .build();
        }
    }

    // =====================================================================
    // Backtest proxies
    // =====================================================================

    @POST
    @Path("/signals/backtest")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response runBacktest(String body) {
        return proxyPost(serviceClient.getSignalServiceUrl() + "/api/signals/backtest", body);
    }

    @GET
    @Path("/signals/backtest/runs")
    public Response listBacktestRuns(
            @QueryParam("configVersionId") Long configVersionId,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        StringBuilder url = new StringBuilder(serviceClient.getSignalServiceUrl())
                .append("/api/signals/backtest/runs?limit=").append(limit);
        if (configVersionId != null) {
            url.append("&configVersionId=").append(configVersionId);
        }
        return proxyResponse(serviceClient.getRaw(url.toString()));
    }

    @GET
    @Path("/signals/backtest/runs/{id}")
    public Response getBacktestRunDetail(@PathParam("id") long id) {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getSignalServiceUrl() + "/api/signals/backtest/runs/" + id));
    }

    // =====================================================================
    // Execution Service proxies
    // =====================================================================

    @GET
    @Path("/execution/accounts")
    public Response listAccounts() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/accounts"));
    }

    @GET
    @Path("/execution/analytics/funnel")
    public Response executionFunnel(@QueryParam("hours") Integer hours) {
        String url = serviceClient.getExecutionUrl() + "/api/execution/analytics/funnel"
                + (hours != null ? "?hours=" + hours : "");
        return proxyResponse(serviceClient.getRaw(url));
    }

    @GET
    @Path("/execution/analytics/strategy-pnl")
    public Response executionStrategyPnl(@QueryParam("days") Integer days) {
        String url = serviceClient.getExecutionUrl() + "/api/execution/analytics/strategy-pnl"
                + (days != null ? "?days=" + days : "");
        return proxyResponse(serviceClient.getRaw(url));
    }

    @POST
    @Path("/execution/accounts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAccount(String body) {
        return propagate(serviceClient.postWithStatus(serviceClient.getExecutionUrl() + "/api/execution/accounts", body));
    }

    @GET
    @Path("/execution/accounts/{id}")
    public Response getAccount(@PathParam("id") Long id) {
        return propagate(serviceClient.getWithStatus(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id));
    }

    @PATCH
    @Path("/execution/accounts/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response patchAccount(@PathParam("id") Long id, String body) {
        return propagate(serviceClient.patchWithStatus(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id, body));
    }

    @DELETE
    @Path("/execution/accounts/{id}")
    public Response deleteAccount(@PathParam("id") Long id) {
        return propagate(serviceClient.deleteWithStatus(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id));
    }

    @GET
    @Path("/execution/accounts/{id}/wallet")
    public Response wallet(@PathParam("id") Long id) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/wallet"));
    }

    @GET
    @Path("/execution/accounts/{id}/positions")
    public Response positions(@PathParam("id") Long id) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/positions"));
    }

    @GET
    @Path("/execution/accounts/{id}/trades")
    public Response trades(@PathParam("id") Long id, @QueryParam("limit") @DefaultValue("50") int limit) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/trades?limit=" + limit));
    }

    @GET
    @Path("/execution/accounts/{id}/trades/history")
    public Response tradeHistory(@PathParam("id") Long id,
                                  @QueryParam("page") @DefaultValue("0") int page,
                                  @QueryParam("pageSize") @DefaultValue("25") int pageSize) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/trades/history?page=" + page + "&pageSize=" + pageSize));
    }

    @POST
    @Path("/execution/accounts/{id}/admin/backfill-closes")
    public Response backfillCloses(@PathParam("id") Long id,
                                    @QueryParam("limit") @DefaultValue("50") int limit) {
        return propagate(serviceClient.postWithStatus(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/admin/backfill-closes?limit=" + limit, ""));
    }

    @POST
    @Path("/execution/accounts/{id}/admin/repair-exit-reasons")
    public Response repairExitReasons(@PathParam("id") Long id,
                                       @QueryParam("limit") @DefaultValue("100") int limit) {
        return propagate(serviceClient.postWithStatus(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/admin/repair-exit-reasons?limit=" + limit, ""));
    }

    @GET
    @Path("/execution/accounts/{id}/events")
    public Response events(@PathParam("id") Long id, @QueryParam("limit") @DefaultValue("100") int limit) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/events?limit=" + limit));
    }

    @GET
    @Path("/execution/accounts/{id}/trades/{tradeId}/why")
    public Response why(@PathParam("id") Long id, @PathParam("tradeId") Long tradeId) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/trades/" + tradeId + "/why"));
    }

    @POST
    @Path("/execution/accounts/{id}/kill-switch")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response killSwitch(@PathParam("id") Long id, String body) {
        return propagate(serviceClient.postWithStatus(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/kill-switch", body));
    }

    @GET
    @Path("/execution/settings")
    public Response getExecutionSettings() {
        return proxyResponse(serviceClient.getRaw(serviceClient.getExecutionUrl() + "/api/execution/settings"));
    }

    @PUT
    @Path("/execution/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateExecutionSettings(String body) {
        return propagate(serviceClient.putWithStatus(serviceClient.getExecutionUrl() + "/api/execution/settings", body));
    }

    @POST
    @Path("/execution/settings/telegram/test")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response testTelegram(String body) {
        return propagate(serviceClient.postWithStatus(
                serviceClient.getExecutionUrl() + "/api/execution/settings/telegram/test",
                body == null ? "" : body));
    }

    @POST
    @Path("/execution/accounts/{id}/close-all")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response closeAll(@PathParam("id") Long id, String body) {
        return propagate(serviceClient.postWithStatus(serviceClient.getExecutionUrl() + "/api/execution/accounts/" + id + "/close-all", body));
    }

    @POST
    @Path("/execution/accounts/{id}/trades/{tradeId}/close")
    public Response closeTrade(@PathParam("id") Long id, @PathParam("tradeId") Long tradeId) {
        return propagate(serviceClient.postWithStatus(serviceClient.getExecutionUrl()
                + "/api/execution/accounts/" + id + "/trades/" + tradeId + "/close", ""));
    }

    /**
     * Forward an upstream (status, body) pair to the client intact. Used by
     * execution-proxy routes where the caller needs to see real 4xx/5xx
     * errors inline (e.g. Bybit key validation failures) rather than a
     * swallowed 200-with-error-body from {@link #proxyResponse(String)}.
     */
    private Response propagate(com.cryptoradar.gateway.client.ServiceClient.RawResponse resp) {
        return Response.status(resp.status()).entity(resp.body()).build();
    }

    // =================================================================
    // Options service proxy routes
    // =================================================================

    @GET
    @Path("/options/chain/{underlying}")
    public Response optionsChain(@PathParam("underlying") String underlying) {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getOptionsUrl() + "/api/options/chain/" + underlying));
    }

    @GET
    @Path("/options/opportunities")
    public Response optionsOpportunities(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("openOnly") @DefaultValue("false") boolean openOnly) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getOptionsUrl()
                + "/api/options/opportunities?limit=" + limit + "&openOnly=" + openOnly));
    }

    @GET
    @Path("/options/opportunities/enriched")
    public Response optionsOpportunitiesEnriched(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("openOnly") @DefaultValue("false") boolean openOnly,
            @QueryParam("includeStale") @DefaultValue("false") boolean includeStale) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getOptionsUrl()
                + "/api/options/opportunities/enriched?limit=" + limit
                + "&openOnly=" + openOnly
                + "&includeStale=" + includeStale));
    }

    @GET
    @Path("/options/stats/hit-rate")
    public Response optionsHitRate() {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getOptionsUrl() + "/api/options/stats/hit-rate"));
    }

    @GET
    @Path("/options/opportunities/{id}")
    public Response optionsOpportunity(@PathParam("id") long id) {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getOptionsUrl() + "/api/options/opportunities/" + id));
    }

    @GET
    @Path("/options/realized-vol/{underlying}")
    public Response optionsRealizedVol(@PathParam("underlying") String underlying,
                                        @QueryParam("days") @DefaultValue("14") int days) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getOptionsUrl()
                + "/api/options/realized-vol/" + underlying + "?days=" + days));
    }

    @GET
    @Path("/options/diagnostic")
    public Response optionsDiagnostic(@QueryParam("underlying") String underlying) {
        String url = serviceClient.getOptionsUrl() + "/api/options/diagnostic"
                + (underlying != null && !underlying.isBlank() ? "?underlying=" + underlying : "");
        return proxyResponse(serviceClient.getRaw(url));
    }

    @GET
    @Path("/options/eval")
    public Response optionsEval() {
        return proxyResponse(serviceClient.getRaw(
                serviceClient.getOptionsUrl() + "/api/options/eval"));
    }

    @GET
    @Path("/options/short-vol/opportunities")
    public Response optionsShortVolOpportunities(@QueryParam("limit") @DefaultValue("50") int limit,
                                                  @QueryParam("openOnly") @DefaultValue("false") boolean openOnly) {
        return proxyResponse(serviceClient.getRaw(serviceClient.getOptionsUrl()
                + "/api/options/short-vol/opportunities?limit=" + limit + "&openOnly=" + openOnly));
    }
}
