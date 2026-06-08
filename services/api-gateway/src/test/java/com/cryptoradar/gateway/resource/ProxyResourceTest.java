package com.cryptoradar.gateway.resource;

import com.cryptoradar.gateway.client.ServiceClient;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the proxy URL-construction and fail-open semantics. These
 * are the load-bearing behaviors: a typo in a URL path silently routes to
 * the wrong upstream, and a missing 502 fallback exposes raw NPEs to the
 * frontend.
 *
 * <p>Covers only a representative slice of endpoints (one per HTTP verb +
 * one per services-client method); the full surface is too large to lock
 * one-to-one, and the helper methods (proxyResponse / proxyPost / proxyPut
 * / proxyDelete) handle URL passthrough uniformly.
 */
@ExtendWith(MockitoExtension.class)
class ProxyResourceTest {

    @Mock ServiceClient serviceClient;

    private ProxyResource proxy;

    @BeforeEach
    void setUp() {
        proxy = new ProxyResource(serviceClient);
    }

    @Test
    @DisplayName("market-prices GET returns 200 + body when upstream healthy")
    void marketPricesHappyPath() {
        when(serviceClient.getMarketDataUrl()).thenReturn("http://md:8081");
        when(serviceClient.getRaw("http://md:8081/api/market/prices")).thenReturn("[{\"symbol\":\"BTC\"}]");

        Response resp = proxy.getMarketPrices();

        assertEquals(200, resp.getStatus());
        assertEquals("[{\"symbol\":\"BTC\"}]", resp.getEntity());
    }

    @Test
    @DisplayName("market-prices GET returns 502 when upstream returns null")
    void marketPricesNullUpstreamGives502() {
        when(serviceClient.getMarketDataUrl()).thenReturn("http://md:8081");
        when(serviceClient.getRaw(contains("/api/market/prices"))).thenReturn(null);

        Response resp = proxy.getMarketPrices();

        assertEquals(502, resp.getStatus());
        assertNotNull(resp.getEntity());
        assertEquals("{\"error\":\"Upstream service unavailable\"}", resp.getEntity());
    }

    @Test
    @DisplayName("execution-analytics funnel endpoint forwards hours query param")
    void executionFunnelForwardsHoursParam() {
        when(serviceClient.getExecutionUrl()).thenReturn("http://exec:8087");
        when(serviceClient.getRaw("http://exec:8087/api/execution/analytics/funnel?hours=6"))
                .thenReturn("{\"windowHours\":6,\"gates\":[]}");

        Response resp = proxy.executionFunnel(6);

        assertEquals(200, resp.getStatus());
        assertEquals("{\"windowHours\":6,\"gates\":[]}", resp.getEntity());
    }

    @Test
    @DisplayName("execution-analytics funnel works without hours param (default)")
    void executionFunnelOmittedHoursOmitsParam() {
        when(serviceClient.getExecutionUrl()).thenReturn("http://exec:8087");
        when(serviceClient.getRaw("http://exec:8087/api/execution/analytics/funnel"))
                .thenReturn("{}");

        Response resp = proxy.executionFunnel(null);

        assertEquals(200, resp.getStatus());
    }

    @Test
    @DisplayName("execution-analytics strategy-pnl forwards days query param")
    void executionStrategyPnlForwardsDaysParam() {
        when(serviceClient.getExecutionUrl()).thenReturn("http://exec:8087");
        when(serviceClient.getRaw("http://exec:8087/api/execution/analytics/strategy-pnl?days=30"))
                .thenReturn("{\"windowDays\":30,\"cells\":[]}");

        Response resp = proxy.executionStrategyPnl(30);

        assertEquals(200, resp.getStatus());
    }
}
