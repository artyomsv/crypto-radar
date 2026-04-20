package com.cryptoradar.execution.client.bybit;

import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.ServerTimeResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Disabled by default — hits live Bybit. Enable manually when validating
 * network path, DNS resolution, TLS handshake.
 */
@QuarkusTest
@Disabled("Hits live Bybit; enable manually")
class BybitV5RestClientSmokeTest {

    @Inject
    BybitV5RestClient client;

    @Test
    void reachesDemoServerAndParsesTime() {
        BybitResponse<ServerTimeResult> resp = client.getServerTime("DEMO");
        assertTrue(resp.isOk(), "retCode should be 0, got: " + resp.retCode() + " " + resp.retMsg());
        assertNotNull(resp.result());
        assertNotNull(resp.result().timeSecond());
    }
}
