package com.cryptoradar.execution;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SanityTest {

    @Test
    void healthEndpointUp() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body(containsString("UP"));
    }
}
