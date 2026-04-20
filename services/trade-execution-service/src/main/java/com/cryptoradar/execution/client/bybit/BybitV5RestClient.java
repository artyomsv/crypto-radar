package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.ServerTimeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Bybit V5 REST client. This skeleton exposes only {@link #getServerTime(String)},
 * which is an unauthenticated probe used for:
 *  - Health check ("is Bybit reachable from this pod?")
 *  - Clock-skew diagnostics (compare server time vs local)
 *
 * Authenticated endpoints (order create, position list, etc.) are added in Task 6.
 */
@ApplicationScoped
public class BybitV5RestClient {

    private static final Logger LOG = Logger.getLogger(BybitV5RestClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    ObjectMapper mapper;

    public BybitResponse<ServerTimeResult> getServerTime(String environment) {
        String base = BybitV5Endpoints.restBaseFor(environment);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v5/market/time"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Bybit /v5/market/time returned status " + resp.statusCode());
            }
            return mapper.readValue(resp.body(),
                    mapper.getTypeFactory().constructParametricType(BybitResponse.class, ServerTimeResult.class));
        } catch (Exception e) {
            LOG.errorf("Bybit /v5/market/time failed for env=%s: %s", environment, e.getMessage());
            throw new RuntimeException("Bybit market-time call failed", e);
        }
    }
}
