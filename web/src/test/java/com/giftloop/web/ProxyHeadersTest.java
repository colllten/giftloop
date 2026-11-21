package com.giftloop.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Behind Fly's TLS proxy, generated URLs use the public https origin
 * ({@code quarkus.http.proxy.*}).
 */
@QuarkusTest
class ProxyHeadersTest {

    /** The headers Fly's proxy sends turn the base URI into the public https URL. */
    @Test
    void forwardedProtoMakesBaseUriHttps() {
        given().header("Host", "giftloop.fly.dev")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Port", "443")
                .header("X-Forwarded-For", "203.0.113.7")
                .when().get("/test-only/base-uri")
                .then().statusCode(200)
                .body(equalTo("https://giftloop.fly.dev/"));
    }

    /** Without proxy headers (local dev), the base URI is the plain request URL. */
    @Test
    void directRequestKeepsHttp() {
        given().when().get("/test-only/base-uri")
                .then().statusCode(200)
                .body(startsWith("http://localhost:"));
    }
}
