package com.giftloop.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Signed-out landing page and its assets.
 */
@QuarkusTest
class HomeResourceTest {

    /** Signed-out home is the landing page: pitch, sign-up/sign-in and join links, HTMX loaded. */
    @Test
    void landingPageForSignedOutVisitors() {
        given().when().get("/")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Wishlists your whole family can share"))
                .body(containsString("How it works"))
                .body(containsString("href=\"/signup\""))
                .body(containsString("href=\"/signin\""))
                .body(containsString("href=\"/join\""))
                .body(containsString("/_static/htmx.org/dist/htmx.min.js"));
    }

    /** The old HTMX demo endpoint is gone. */
    @Test
    void pingDemoRemoved() {
        given().when().get("/fragments/ping").then().statusCode(404);
    }

    /** HTMX script is served from the mvnpm dependency. */
    @Test
    void htmxAssetIsServed() {
        given().when().get("/_static/htmx.org/dist/htmx.min.js")
                .then().statusCode(200);
    }

    /** Pages link the shared stylesheet, and it and its fonts are served. */
    @Test
    void designAssetsAreServed() {
        given().when().get("/")
                .then().statusCode(200)
                .body(containsString("href=\"/assets/app.css\""));
        given().when().get("/assets/app.css")
                .then().statusCode(200)
                .contentType(containsString("text/css"))
                .body(containsString("--color-primary"));
        given().when().get("/assets/fonts/nunito-sans-latin.woff2").then().statusCode(200);
        given().when().get("/assets/fonts/bricolage-grotesque-latin.woff2").then().statusCode(200);
    }
}
