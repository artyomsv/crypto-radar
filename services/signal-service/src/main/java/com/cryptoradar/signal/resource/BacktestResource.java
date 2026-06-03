package com.cryptoradar.signal.resource;

import com.cryptoradar.signal.backtest.BacktestRepository;
import com.cryptoradar.signal.backtest.BacktestRun;
import com.cryptoradar.signal.backtest.BacktestService;
import com.cryptoradar.signal.backtest.BacktestTrade;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Tier 1 backtest feature.
 *
 * <p>All paths are under {@code /api/signals/backtest} and proxied through
 * the api-gateway.
 */
@Path("/api/signals/backtest")
@Produces(MediaType.APPLICATION_JSON)
public class BacktestResource {

    private static final int MAX_LIST_LIMIT = 200;
    private static final int SUPPORTED_TIER = 1;

    private final BacktestService backtestService;
    private final BacktestRepository backtestRepository;

    public BacktestResource(BacktestService backtestService,
                             BacktestRepository backtestRepository) {
        this.backtestService = backtestService;
        this.backtestRepository = backtestRepository;
    }

    /**
     * Runs a Tier 1 backtest synchronously.
     *
     * @param body {@code { "configVersionId": N, "periodStart": ISO, "periodEnd": ISO, "tier": 1 }}
     * @return 200 with aggregate {@link BacktestRun}, 400 on invalid input
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response runBacktest(BacktestRequest body) {
        if (body == null) {
            return badRequest("Request body is required");
        }
        if (body.tier() != SUPPORTED_TIER) {
            return badRequest("Only tier=1 is supported; tier=" + body.tier() + " is not implemented");
        }
        if (body.configVersionId() == null) {
            return badRequest("configVersionId is required");
        }

        Instant start = parseInstant(body.periodStart(), "periodStart");
        Instant end = parseInstant(body.periodEnd(), "periodEnd");

        BacktestRun run = backtestService.runTier1(
                body.configVersionId(), start, end, body.alignmentFloor());
        return Response.ok(run).build();
    }

    /**
     * Lists backtest runs, optionally filtered by config version.
     */
    @GET
    @Path("/runs")
    @Transactional
    public List<BacktestRun> listRuns(
            @QueryParam("configVersionId") Long configVersionId,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        int clampedLimit = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        if (configVersionId != null) {
            return backtestRepository.findByConfigVersion(configVersionId, clampedLimit);
        }
        return backtestRepository.findRecent(clampedLimit);
    }

    /**
     * Returns a single run with its per-trade detail rows.
     */
    @GET
    @Path("/runs/{id}")
    @Transactional
    public Response getRunDetail(@PathParam("id") long id) {
        BacktestRun run = backtestRepository.findRunById(id)
                .orElseThrow(() -> new NotFoundException("Backtest run not found: " + id));
        List<BacktestTrade> trades = backtestRepository.findTradesByRun(id);
        return Response.ok(new BacktestRunDetail(run, trades)).build();
    }

    // --- DTOs ---

    public record BacktestRequest(
            Long configVersionId,
            String periodStart,
            String periodEnd,
            int tier,
            Integer alignmentFloor
    ) {}

    /**
     * Flat shape: BacktestRun fields at top level via {@code @JsonUnwrapped},
     * plus a {@code trades} array. Matches the {@code BacktestRunDetail}
     * TypeScript interface in the frontend (extends BacktestRun + trades).
     */
    public record BacktestRunDetail(
            @com.fasterxml.jackson.annotation.JsonUnwrapped BacktestRun run,
            List<BacktestTrade> trades) {}

    // --- helpers ---

    private Instant parseInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(fieldName + " is required");
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new BadRequestException(fieldName + " must be ISO-8601: " + value);
        }
    }

    private Response badRequest(String message) {
        return Response.status(400).entity(Map.of("error", message)).build();
    }
}
