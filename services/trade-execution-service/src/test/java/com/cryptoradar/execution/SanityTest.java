package com.cryptoradar.execution;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(SanityTest.SanityProfile.class)
class SanityTest {

    public static class SanityProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            return Map.ofEntries(
                    Map.entry("execution.master-key", Base64.getEncoder().encodeToString(k)),
                    Map.entry("execution.master-key-prev", Base64.getEncoder().encodeToString(k)),
                    Map.entry("bybit.rest-base-override.MAINNET", "https://api.bybit.com"),
                    Map.entry("bybit.rest-base-override.DEMO", "https://api-demo.bybit.com")
            );
        }
    }

    @Test
    void healthEndpointUp() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body(containsString("UP"));
    }
}
