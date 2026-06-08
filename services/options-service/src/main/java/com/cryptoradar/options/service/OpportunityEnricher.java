package com.cryptoradar.options.service;

import com.cryptoradar.options.model.OptionOpportunity;
import com.cryptoradar.options.model.OptionSnapshot;
import com.cryptoradar.options.repository.OptionSnapshotRepository;
import com.cryptoradar.options.resource.dto.EnrichedOpportunityView;
import com.cryptoradar.options.resource.dto.OptionLegView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps {@link OptionOpportunity} with live per-leg snapshots, pre-summed
 * Greeks, AND a live re-score of the same inputs the scorer used at detection
 * time. The live values let the UI surface when an opportunity has "gone
 * stale" — IV reprice, signal decay, spot moving past strikes can all break
 * the original thesis without anyone noticing.
 */
@ApplicationScoped
public class OpportunityEnricher {

    private static final Logger LOG = Logger.getLogger(OpportunityEnricher.class);

    @Inject OptionSnapshotRepository snapshotRepo;
    @Inject RealizedVolService rvService;
    @Inject SignalOverlayService signalOverlayService;

    /**
     * Confidence floor below which the UI marks the opportunity STALE and
     * hides it by default. Mirrors {@code OpportunityScorer.confidenceThreshold}
     * default of 70 minus a 20-point hysteresis band — re-detections shouldn't
     * flicker as live values drift near the threshold.
     */
    @ConfigProperty(name = "options.opportunity.stale-threshold", defaultValue = "50.0")
    double staleThreshold;

    /**
     * Batched enrichment for the {@code /opportunities/enriched} endpoint.
     * Replaces the per-opportunity N+1 pattern (5 queries × N) with at most
     * 1 snapshot batch + (U × 2) per-underlying queries, where U is the
     * number of distinct underlyings (usually 3–7). Drops the 100-opportunity
     * call from ~10s to under 1s.
     */
    public List<EnrichedOpportunityView> enrichBatch(List<OptionOpportunity> opportunities) {
        if (opportunities == null || opportunities.isEmpty()) return List.of();

        long t0 = System.nanoTime();
        // 1) One query for ALL leg snapshots.
        Set<String> legSymbols = new HashSet<>(opportunities.size() * 2);
        for (OptionOpportunity o : opportunities) {
            if (o.getCallSymbol() != null) legSymbols.add(o.getCallSymbol());
            if (o.getPutSymbol() != null) legSymbols.add(o.getPutSymbol());
        }
        Map<String, OptionSnapshot> snapshots = snapshotRepo.latestForSymbols(legSymbols);
        long t1 = System.nanoTime();

        // 2) One RV + one signal-overlay query per distinct underlying. Memoized
        //    for the duration of this request — the same underlying repeats
        //    across many opportunities, no point recomputing.
        Map<String, Double> rvByUnderlying = new HashMap<>();
        Map<String, Double> signalByUnderlying = new HashMap<>();
        Set<String> uniqueUnderlyings = new HashSet<>();
        for (OptionOpportunity o : opportunities) {
            if (o.getUnderlying() != null) uniqueUnderlyings.add(o.getUnderlying());
        }
        for (String u : uniqueUnderlyings) {
            rvByUnderlying.put(u, rvService.computeAnnualized(u, 14));
        }
        long t2 = System.nanoTime();
        for (String u : uniqueUnderlyings) {
            signalByUnderlying.put(u, signalOverlayService.score(u));
        }
        long t3 = System.nanoTime();

        // 3) Assemble views using pure-lookup data — zero DB I/O in the loop.
        List<EnrichedOpportunityView> out = new java.util.ArrayList<>(opportunities.size());
        for (OptionOpportunity o : opportunities) {
            out.add(enrichFromMaps(o, snapshots, rvByUnderlying, signalByUnderlying));
        }
        long t4 = System.nanoTime();
        // Slow path always emits WARN — anything over 1s is a regression we
        // want to see immediately. Healthy path is DEBUG so we don't spam
        // logs at steady state when caches are warm.
        long totalMs = (t4 - t0) / 1_000_000;
        if (totalMs > 1000) {
            LOG.warnf("SLOW enrichBatch n=%d legs=%d underlyings=%d snapshots=%dms rv=%dms signal=%dms assemble=%dms total=%dms",
                    opportunities.size(), legSymbols.size(), uniqueUnderlyings.size(),
                    (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
                    (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000, totalMs);
        } else if (LOG.isDebugEnabled()) {
            LOG.debugf("enrichBatch n=%d legs=%d underlyings=%d snapshots=%dms rv=%dms signal=%dms assemble=%dms total=%dms",
                    opportunities.size(), legSymbols.size(), uniqueUnderlyings.size(),
                    (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
                    (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000, totalMs);
        }
        return out;
    }

    private EnrichedOpportunityView enrichFromMaps(
            OptionOpportunity o,
            Map<String, OptionSnapshot> snapshots,
            Map<String, Double> rvByUnderlying,
            Map<String, Double> signalByUnderlying) {

        OptionSnapshot callSnap = o.getCallSymbol() == null ? null : snapshots.get(o.getCallSymbol());
        OptionSnapshot putSnap = o.getPutSymbol() == null ? null : snapshots.get(o.getPutSymbol());
        OptionLegView callLeg = callSnap == null ? null : OptionLegView.from(callSnap);
        OptionLegView putLeg = putSnap == null ? null : OptionLegView.from(putSnap);

        Double netDelta = sumLegs(callLeg, putLeg, OptionLegView::delta);
        Double netGamma = sumLegs(callLeg, putLeg, OptionLegView::gamma);
        Double netTheta = sumLegs(callLeg, putLeg, OptionLegView::theta);
        Double netVega = sumLegs(callLeg, putLeg, OptionLegView::vega);
        Double totalOi = sumLegs(callLeg, putLeg, OptionLegView::openInterest);
        Double totalVol = sumLegs(callLeg, putLeg, OptionLegView::volume24h);

        // Live score uses prefetched RV + signal overlay; reuses callSnap for spot.
        LiveScore live = computeLiveFromMaps(o, callLeg, putLeg, callSnap,
                rvByUnderlying, signalByUnderlying);

        return new EnrichedOpportunityView(
                o.getId(), o.getDetectedAt(), o.getUnderlying(), o.getExpiry(),
                o.getStrikeCall(), o.getStrikePut(), o.getCallSymbol(), o.getPutSymbol(),
                o.getStranglePremium(), o.getImpliedVolAtm(),
                o.getRealizedVol7d(), o.getRealizedVol14d(), o.getIvRvSpread(),
                o.getSignalOverlay(), o.getConfidence(), o.getMetadata(),
                o.getRealizedMovePct(), o.getOutcomePnlPct(), o.getOutcomeResolvedAt(),
                callLeg, putLeg, netDelta, netGamma, netTheta, netVega, totalOi, totalVol,
                live.premium, live.iv, live.ivRvGap, live.signalOverlay,
                live.confidence, live.spot, live.isStale, live.staleReason);
    }

    LiveScore computeLiveFromMaps(OptionOpportunity o,
                                   OptionLegView callLeg, OptionLegView putLeg,
                                   OptionSnapshot callSnap,
                                   Map<String, Double> rvByUnderlying,
                                   Map<String, Double> signalByUnderlying) {
        LiveScore s = new LiveScore();

        if (callLeg != null && putLeg != null
                && callLeg.ask() != null && putLeg.ask() != null
                && callLeg.ask() > 0 && putLeg.ask() > 0) {
            s.premium = callLeg.ask() + putLeg.ask();
        }

        Double cIv = callLeg == null ? null : callLeg.impliedVol();
        Double pIv = putLeg == null ? null : putLeg.impliedVol();
        if (cIv != null && pIv != null) s.iv = (cIv + pIv) / 2.0;
        else if (cIv != null) s.iv = cIv;
        else if (pIv != null) s.iv = pIv;

        Double rv14 = rvByUnderlying.get(o.getUnderlying());
        Double ivPct = s.iv == null ? null : s.iv * 100.0;
        if (ivPct != null && rv14 != null && rv14 > 0) {
            s.ivRvGap = rv14 - ivPct;
            double gapScore = Math.max(0.0, Math.min(100.0, (rv14 - ivPct) / rv14 * 100.0));
            s.signalOverlay = signalByUnderlying.getOrDefault(o.getUnderlying(), 0.0);
            s.confidence = 0.6 * gapScore + 0.4 * s.signalOverlay;
        } else {
            s.confidence = o.getConfidence();
            s.signalOverlay = o.getSignalOverlay();
            s.ivRvGap = o.getIvRvSpread();
        }

        // Spot from the same callSnap already fetched — no extra query.
        // (Previous implementation issued a redundant latestForSymbol call here.)
        if (callSnap != null) {
            s.spot = callSnap.getUnderlyingPx();
        }

        s.classifyStaleness(o, staleThreshold);
        return s;
    }

    public EnrichedOpportunityView enrich(OptionOpportunity o) {
        OptionLegView callLeg = snapshotRepo.latestForSymbol(o.getCallSymbol())
                .map(OptionLegView::from).orElse(null);
        OptionLegView putLeg = snapshotRepo.latestForSymbol(o.getPutSymbol())
                .map(OptionLegView::from).orElse(null);

        Double netDelta = sumLegs(callLeg, putLeg, OptionLegView::delta);
        Double netGamma = sumLegs(callLeg, putLeg, OptionLegView::gamma);
        Double netTheta = sumLegs(callLeg, putLeg, OptionLegView::theta);
        Double netVega = sumLegs(callLeg, putLeg, OptionLegView::vega);
        Double totalOi = sumLegs(callLeg, putLeg, OptionLegView::openInterest);
        Double totalVol = sumLegs(callLeg, putLeg, OptionLegView::volume24h);

        LiveScore live = computeLive(o, callLeg, putLeg);

        return new EnrichedOpportunityView(
                o.getId(), o.getDetectedAt(), o.getUnderlying(), o.getExpiry(),
                o.getStrikeCall(), o.getStrikePut(), o.getCallSymbol(), o.getPutSymbol(),
                o.getStranglePremium(), o.getImpliedVolAtm(),
                o.getRealizedVol7d(), o.getRealizedVol14d(), o.getIvRvSpread(),
                o.getSignalOverlay(), o.getConfidence(), o.getMetadata(),
                o.getRealizedMovePct(), o.getOutcomePnlPct(), o.getOutcomeResolvedAt(),
                callLeg, putLeg, netDelta, netGamma, netTheta, netVega, totalOi, totalVol,
                live.premium, live.iv, live.ivRvGap, live.signalOverlay,
                live.confidence, live.spot, live.isStale, live.staleReason);
    }

    /**
     * Recompute the inputs the scorer used. Same formula
     * (0.6 × iv_rv_gap_score + 0.4 × signal_overlay) so live confidence is
     * directly comparable to the entry confidence.
     */
    LiveScore computeLive(OptionOpportunity o, OptionLegView callLeg, OptionLegView putLeg) {
        LiveScore s = new LiveScore();

        // Live premium = ask + ask across both legs (what a fresh entry would cost).
        if (callLeg != null && putLeg != null
                && callLeg.ask() != null && putLeg.ask() != null
                && callLeg.ask() > 0 && putLeg.ask() > 0) {
            s.premium = callLeg.ask() + putLeg.ask();
        }

        // Live IV ATM = mean of leg IVs.
        Double cIv = callLeg == null ? null : callLeg.impliedVol();
        Double pIv = putLeg == null ? null : putLeg.impliedVol();
        if (cIv != null && pIv != null) s.iv = (cIv + pIv) / 2.0;
        else if (cIv != null) s.iv = cIv;
        else if (pIv != null) s.iv = pIv;

        // Live RV 14d — recomputed from the candles hypertable (cheap, indexed).
        Double rv14 = rvService.computeAnnualized(o.getUnderlying(), 14);

        // IV stored on the snapshot is decimal (e.g. 0.295), confidence math
        // expects percent (29.5). Match what OpportunityScorer does.
        Double ivPct = s.iv == null ? null : s.iv * 100.0;
        if (ivPct != null && rv14 != null && rv14 > 0) {
            s.ivRvGap = rv14 - ivPct;
            double gapScore = Math.max(0.0, Math.min(100.0, (rv14 - ivPct) / rv14 * 100.0));
            s.signalOverlay = signalOverlayService.score(o.getUnderlying());
            s.confidence = 0.6 * gapScore + 0.4 * s.signalOverlay;
        } else {
            // Fall back to entry-time confidence if we can't recompute.
            s.confidence = o.getConfidence();
            s.signalOverlay = o.getSignalOverlay();
            s.ivRvGap = o.getIvRvSpread();
        }

        // Underlying spot from the call leg's snapshot.
        if (callLeg != null && callLeg.symbol() != null) {
            s.spot = snapshotRepo.latestForSymbol(callLeg.symbol())
                    .map(snap -> snap.getUnderlyingPx())
                    .orElse(null);
        }

        s.classifyStaleness(o, staleThreshold);
        return s;
    }

    static class LiveScore {
        Double premium;
        Double iv;
        Double ivRvGap;
        Double signalOverlay;
        Double confidence;
        Double spot;
        boolean isStale;
        String staleReason;

        /**
         * Mark stale when:
         * <ul>
         *   <li>Detection past the expiry date — trivial cleanup</li>
         *   <li>Live confidence has dropped below the stale threshold (default 50)</li>
         *   <li>Spot has moved outside the strangle's strike range with a 2%
         *       buffer — the entry thesis is gone; a fresh straddle would
         *       choose different strikes.</li>
         * </ul>
         */
        void classifyStaleness(OptionOpportunity o, double threshold) {
            if (o.getExpiry() != null
                    && o.getExpiry().atStartOfDay()
                        .toInstant(java.time.ZoneOffset.UTC)
                        .isBefore(java.time.Instant.now())) {
                isStale = true;
                staleReason = "Expiry passed";
                return;
            }
            if (confidence != null && confidence < threshold) {
                isStale = true;
                staleReason = String.format(
                        "Live confidence %.0f below threshold %.0f",
                        confidence, threshold);
                return;
            }
            if (spot != null) {
                double lo = Math.min(o.getStrikeCall(), o.getStrikePut());
                double hi = Math.max(o.getStrikeCall(), o.getStrikePut());
                double bufferPct = 0.02;
                double loBound = lo * (1 - bufferPct);
                double hiBound = hi * (1 + bufferPct);
                if (spot < loBound || spot > hiBound) {
                    isStale = true;
                    staleReason = String.format(
                            "Spot %.2f moved outside strike band [%.2f, %.2f]",
                            spot, loBound, hiBound);
                }
            }
        }
    }

    @FunctionalInterface
    private interface LegField {
        Double get(OptionLegView leg);
    }

    private static Double sumLegs(OptionLegView a, OptionLegView b, LegField f) {
        Double va = a == null ? null : f.get(a);
        Double vb = b == null ? null : f.get(b);
        if (va == null && vb == null) return null;
        return (va == null ? 0.0 : va) + (vb == null ? 0.0 : vb);
    }
}
