package com.giftloop.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Readiness includes the datasource check against local Supabase.
 */
@QuarkusTest
class HealthTest {

    /** Readiness reports UP when the database is reachable. */
    @Test
    void readinessIsUp() {
        given().when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", is("UP"));
    }
}
