package com.cryptoradar.signal.resource;

import com.cryptoradar.signal.model.PerformanceReport;
import com.cryptoradar.signal.model.SignalOutcome;
import com.cryptoradar.signal.model.SignalOutcomeView;
import com.cryptoradar.signal.model.SignalOverview;
import com.cryptoradar.signal.model.TradingSignal;
import com.cryptoradar.signal.repository.SignalOutcomeRepository;
import com.cryptoradar.signal.service.PerformanceMetricsService;
import com.cryptoradar.signal.service.SignalService;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Map;

@Path("/api/signals")
@Produces(MediaType.APPLICATION_JSON)
public class SignalResource {

    private static final int MIN_PERIOD_DAYS = 1;
    private static final int MAX_PERIOD_DAYS = 365;
    private static final int MIN_OUTCOMES_LIMIT = 1;
    private static final int MAX_OUTCOMES_LIMIT = 500;

    private final SignalService signalService;
    private final PerformanceMetricsService metricsService;
    private final SignalOutcomeRepository outcomeRepository;

    public SignalResource(SignalService signalService,
                          PerformanceMetricsService metricsService,
                          SignalOutcomeRepository outcomeRepository) {
        this.signalService = signalService;
        this.metricsService = metricsService;
        this.outcomeRepository = outcomeRepository;
    }

    @GET
    @Path("/overview")
    public SignalOverview getOverview() {
        return signalService.getSignalOverview();
    }

    @GET
    @Path("/{symbol}")
    public Response getSignal(@PathParam("symbol") String symbol) {
        TradingSignal signal = signalService.getSignal(symbol.toUpperCase());
        if (signal == null) {
            return Response.status(404)
                    .entity(Map.of("error", "No signal data for " + symbol.toUpperCase()))
                    .build();
        }
        return Response.ok(signal).build();
    }

    @GET
    @Path("/{symbol}/raw-data")
    public Response getRawSignalData(@PathParam("symbol") String symbol) {
        Map<String, Object> rawData = signalService.getRawSignalData(symbol.toUpperCase());
        return Response.ok(rawData).build();
    }

    @POST
    @Path("/{symbol}/ai-analysis")
    public Response requestAiAnalysis(@PathParam("symbol") String symbol) {
        String analysis = signalService.requestAiAnalysis(symbol.toUpperCase());
        return Response.ok(Map.of(
                "symbol", symbol.toUpperCase(),
                "analysis", analysis,
                "timestamp", java.time.Instant.now().toString()
        )).build();
    }

    /**
     * Signal performance metrics over the given lookback window.
     * Answers: "if I had traded every signal, what would my P&L be?"
     */
    @GET
    @Path("/metrics")
    public PerformanceReport getMetrics(
            @QueryParam("periodDays") @DefaultValue("30") int periodDays) {
        int clamped = clampPeriod(periodDays);
        return metricsService.buildReport(clamped);
    }

    /**
     * Raw trade ledger: the most recent outcomes, optionally filtered by
     * symbol. Supplies the timestamped list of trades and also powers the
     * per-symbol chart overlay (all outcomes for one symbol in one call).
     */
    @GET
    @Path("/outcomes")
    @Transactional
    public List<SignalOutcomeView> getOutcomes(
            @QueryParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        int clampedLimit = clampOutcomesLimit(limit);
        List<SignalOutcome> outcomes = (symbol == null || symbol.isBlank())
                ? outcomeRepository.findRecent(clampedLimit)
                : outcomeRepository.findRecentBySymbol(symbol.toUpperCase(), clampedLimit);
        return outcomes.stream().map(SignalOutcomeView::from).toList();
    }

    private int clampPeriod(int periodDays) {
        if (periodDays < MIN_PERIOD_DAYS) return MIN_PERIOD_DAYS;
        if (periodDays > MAX_PERIOD_DAYS) return MAX_PERIOD_DAYS;
        return periodDays;
    }

    private int clampOutcomesLimit(int limit) {
        if (limit < MIN_OUTCOMES_LIMIT) return MIN_OUTCOMES_LIMIT;
        if (limit > MAX_OUTCOMES_LIMIT) return MAX_OUTCOMES_LIMIT;
        return limit;
    }
}
