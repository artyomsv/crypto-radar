package com.cryptoradar.analytics.service;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

@Path("/api/news")
@RegisterRestClient(configKey = "news-service")
@Produces(MediaType.APPLICATION_JSON)
public interface NewsServiceClient {

    @GET
    @Path("/sentiment/{symbol}")
    Map<String, Object> getSentiment(@PathParam("symbol") String symbol);
}
