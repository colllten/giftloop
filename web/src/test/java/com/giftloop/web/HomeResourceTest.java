package com.giftloop.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Landing page, HTMX asset and fragment rendering.
 */
@QuarkusTest
class HomeResourceTest {

    /** Landing page renders and references the bundled HTMX script. */
    @Test
    void indexRendersWithHtmx() {
        given().when().get("/")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("<h1>GiftLoop</h1>"))
                .body(containsString("/_static/htmx.org/dist/htmx.min.js"))
                .body(containsString("hx-get=\"/fragments/ping\""));
    }

    /** HTMX script is served from the mvnpm dependency. */
    @Test
    void htmxAssetIsServed() {
        given().when().get("/_static/htmx.org/dist/htmx.min.js")
                .then().statusCode(200);
    }

    /** Fragment endpoint returns a snippet, not a full page. */
    @Test
    void pingReturnsFragment() {
        given().when().get("/fragments/ping")
                .then().statusCode(200)
                .body(containsString("Server is up"))
                .body(not(containsString("<html")));
    }
}
