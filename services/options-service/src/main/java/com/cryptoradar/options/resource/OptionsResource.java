package com.cryptoradar.options.resource;

import com.cryptoradar.options.repository.OptionOpportunityRepository;
import com.cryptoradar.options.repository.OptionSnapshotRepository;
import com.cryptoradar.options.resource.dto.ChainRowView;
import com.cryptoradar.options.resource.dto.EnrichedOpportunityView;
import com.cryptoradar.options.resource.dto.HitRateView;
import com.cryptoradar.options.resource.dto.OpportunityView;
import com.cryptoradar.options.service.OpportunityEnricher;
import com.cryptoradar.options.service.RealizedVolService;
import com.cryptoradar.options.service.OptionsCollectorService;
import com.cryptoradar.options.service.OpportunityScorer;
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

import java.util.List;
import java.util.Map;

@Path("/api/options")
@Produces(MediaType.APPLICATION_JSON)
public class OptionsResource {

    private final OptionSnapshotRepository snapshotRepo;
    private final OptionOpportunityRepository opportunityRepo;
    private final RealizedVolService rvService;
    private final OptionsCollectorService collector;
    private final OpportunityScorer scorer;
    private final OpportunityEnricher enricher;

    public OptionsResource(OptionSnapshotRepository snapshotRepo,
                            OptionOpportunityRepository opportunityRepo,
                            RealizedVolService rvService,
                            OptionsCollectorService collector,
                            OpportunityScorer scorer,
                            OpportunityEnricher enricher) {
        this.snapshotRepo = snapshotRepo;
        this.opportunityRepo = opportunityRepo;
        this.rvService = rvService;
        this.collector = collector;
        this.scorer = scorer;
        this.enricher = enricher;
    }

    /**
     * Debug endpoint — manually trigger one cycle of tickers + scoring for
     * one underlying. Useful when the scheduler hasn't kicked off yet or
     * for one-shot diagnostics.
     */
    @POST
    @Path("/admin/poll/{underlying}")
    public Response poll(@PathParam("underlying") String underlying) {
        String u = underlying.toUpperCase();
        collector.collectTickers(u);
        scorer.scoreAllUnderlyings(java.util.List.of(u));
        long count = snapshotRepo.latestChain(u).size();
        return Response.ok(Map.of(
                "underlying", u,
                "contractsStored", count)).build();
    }

    @GET
    @Path("/chain/{underlying}")
    public List<ChainRowView> chain(@PathParam("underlying") String underlying) {
        return snapshotRepo.latestChain(underlying.toUpperCase()).stream()
                .map(ChainRowView::from)
                .toList();
    }

    @GET
    @Path("/opportunities")
    public List<OpportunityView> opportunities(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("openOnly") @DefaultValue("false") boolean openOnly) {
        int clamped = Math.min(Math.max(limit, 1), 200);
        var entities = openOnly
                ? opportunityRepo.findOpen(clamped)
                : opportunityRepo.findRecent(clamped);
        return entities.stream().map(OpportunityView::from).toList();
    }

    /**
     * Decision-ready opportunities — same set as {@link #opportunities} but
     * with the call/put leg snapshots attached and per-pair Greeks summed.
     * The UI watchlist uses this; the original endpoint is kept for the
     * future auto-quote bot which doesn't need the leg details.
     */
    @GET
    @Path("/opportunities/enriched")
    public List<EnrichedOpportunityView> opportunitiesEnriched(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("openOnly") @DefaultValue("false") boolean openOnly,
            @QueryParam("includeStale") @DefaultValue("false") boolean includeStale) {
        int clamped = Math.min(Math.max(limit, 1), 200);
        var entities = openOnly
                ? opportunityRepo.findOpen(clamped)
                : opportunityRepo.findRecent(clamped);
        var enriched = entities.stream().map(enricher::enrich);
        if (!includeStale) {
            enriched = enriched.filter(e -> !e.isStale());
        }
        return enriched.toList();
    }

    /**
     * Historical win rate by (underlying, confidence-bucket). Empty array
     * while no opportunities have been resolved yet — the UI hides any
     * bucket where {@code sampleSize < 10}.
     */
    @GET
    @Path("/stats/hit-rate")
    public List<HitRateView> hitRate() {
        List<Object[]> rows = opportunityRepo.hitRateByBucket();
        return rows.stream().map(r -> new HitRateView(
                (String) r[0],
                (String) r[1],
                ((Number) r[2]).longValue(),
                r[3] == null ? null : ((Number) r[3]).doubleValue(),
                r[4] == null ? null : ((Number) r[4]).doubleValue()
        )).toList();
    }

    @GET
    @Path("/opportunities/{id}")
    public OpportunityView opportunity(@PathParam("id") long id) {
        return opportunityRepo.findById(id)
                .map(OpportunityView::from)
                .orElseThrow(() -> new NotFoundException("Opportunity not found: " + id));
    }

    @GET
    @Path("/realized-vol/{underlying}")
    public Response realizedVol(@PathParam("underlying") String underlying,
                                 @QueryParam("days") @DefaultValue("14") int days) {
        Double rv = rvService.computeAnnualized(underlying.toUpperCase(), days);
        if (rv == null) {
            return Response.status(404)
                    .entity(Map.of("error", "Insufficient candle history for " + underlying))
                    .build();
        }
        return Response.ok(Map.of(
                "underlying", underlying.toUpperCase(),
                "lookbackDays", days,
                "annualizedPct", rv)).build();
    }
}
