package com.cryptoradar.options.client;

import com.cryptoradar.options.client.dto.BybitResponse;
import com.cryptoradar.options.client.dto.HistoricalVolV5;
import com.cryptoradar.options.client.dto.OptionInstrumentV5;
import com.cryptoradar.options.client.dto.OptionTickerV5;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Minimal client for Bybit V5 public options endpoints.
 *
 * <p>All three endpoints are unauthenticated, so no signing — we keep this
 * client self-contained inside options-service rather than depending on
 * {@code BybitV5RestClient} in trade-execution-service. Different concerns,
 * different lifecycles.
 *
 * <p>Bybit weight budget: 1200/min. Our usage is roughly 7 underlyings × 1
 * tickers call/min = 7 weight/min, well within budget.
 */
@ApplicationScoped
public class BybitOptionsClient {

    private static final Logger LOG = Logger.getLogger(BybitOptionsClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "bybit.rest-base")
    String baseUrl;

    /** Bybit list-style wrapper: {@code result.list = [...]}. */
    public record ListResult<T>(
            @com.fasterxml.jackson.annotation.JsonProperty("list") List<T> list) {}

    /**
     * GET /v5/market/instruments-info?category=option&baseCoin=BTC
     * Returns the full option chain (strikes × expiries) for one underlying.
     * No client-side filtering — caller picks contracts within their expiry window.
     */
    public BybitResponse<ListResult<OptionInstrumentV5>> getOptionInstruments(String baseCoin) {
        String qs = "category=option&baseCoin=" + baseCoin + "&limit=1000";
        return get("/v5/market/instruments-info", qs, listType(OptionInstrumentV5.class));
    }

    /**
     * GET /v5/market/tickers?category=option&baseCoin=BTC
     * Live IV / bid / ask / Greeks / OI / volume per contract.
     */
    public BybitResponse<ListResult<OptionTickerV5>> getOptionTickers(String baseCoin) {
        String qs = "category=option&baseCoin=" + baseCoin;
        return get("/v5/market/tickers", qs, listType(OptionTickerV5.class));
    }

    /**
     * GET /v5/market/historical-volatility?category=option&baseCoin=BTC&period=N
     * Bybit's published HV over period N days. We use 7, 14, 30.
     */
    public BybitResponse<List<HistoricalVolV5>> getHistoricalVolatility(String baseCoin, int periodDays) {
        String qs = "category=option&baseCoin=" + baseCoin + "&period=" + periodDays;
        // HV endpoint returns result as a bare array, not wrapped in {list:[...]}.
        JavaType wrapperType = mapper.getTypeFactory()
                .constructParametricType(BybitResponse.class,
                        mapper.getTypeFactory()
                                .constructCollectionType(List.class, HistoricalVolV5.class));
        return execute("/v5/market/historical-volatility", qs, wrapperType);
    }

    // -------------------------------------------------------------------------

    private <T> BybitResponse<ListResult<T>> get(String path, String qs, JavaType itemType) {
        JavaType listResultType = mapper.getTypeFactory()
                .constructParametricType(ListResult.class, itemType);
        JavaType envelopeType = mapper.getTypeFactory()
                .constructParametricType(BybitResponse.class, listResultType);
        return execute(path, qs, envelopeType);
    }

    private <T> BybitResponse<T> execute(String path, String qs, JavaType envelopeType) {
        URI uri = URI.create(baseUrl + path + (qs.isEmpty() ? "" : "?" + qs));
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warnf("Bybit %s HTTP %d body=%s", path, resp.statusCode(),
                        truncate(resp.body(), 200));
                throw new RuntimeException("Bybit " + path + " status=" + resp.statusCode());
            }
            return mapper.readValue(resp.body(), envelopeType);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Bybit " + path + " call failed: " + e.getMessage(), e);
        }
    }

    private JavaType listType(Class<?> itemClass) {
        return mapper.getTypeFactory().constructType(itemClass);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
