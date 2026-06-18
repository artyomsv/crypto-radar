package com.cryptoradar.derivatives.resource;

import com.cryptoradar.derivatives.service.FeedStalenessMonitor;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Exposes per-feed freshness so the staleness state is inspectable on demand
 * (curl, dashboards, smoke checks) rather than only via the scheduled WARN log.
 */
@Path("/api/derivatives/feed-health")
public class FeedHealthResource {

    @Inject
    FeedStalenessMonitor monitor;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<FeedStalenessMonitor.FeedFreshness> health() {
        return monitor.report();
    }
}
