package com.giftloop.observability;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Readiness (on the management port) covers the database and Supabase Auth, and
 * management endpoints are not served on the public port.
 */
@QuarkusTest
class HealthTest {

    /** Management root already includes {@code /q}. */
    @TestHTTPResource(value = "/health/ready", management = true)
    URL readiness;

    /** Readiness reports UP, including the Supabase Auth check, when local Supabase is running. */
    @Test
    void readinessIsUp() {
        given().when().get(readiness)
                .then().statusCode(200)
                .body("status", is("UP"))
                .body("checks.find { it.name == '" + SupabaseAuthReadiness.NAME + "' }.status", is("UP"))
                .body("checks.name", hasItem(SupabaseAuthReadiness.NAME));
    }

    /** Health is not reachable on the public HTTP port. */
    @Test
    void notOnPublicPort() {
        given().when().get("/q/health/ready").then().statusCode(404);
    }
}
