package com.cryptoradar.gateway.resource;

import com.cryptoradar.gateway.event.SseManager;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/api/stream")
public class SseResource {

    @Inject
    SseManager sseManager;

    @GET
    @Path("/prices")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> streamPrices() {
        return sseManager.getPricesStream();
    }

    @GET
    @Path("/news")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> streamNews() {
        return sseManager.getNewsStream();
    }

    @GET
    @Path("/analytics")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> streamAnalytics() {
        return sseManager.getAnalyticsStream();
    }

    @GET
    @Path("/all")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> streamAll() {
        return sseManager.getAllStreams();
    }
}
