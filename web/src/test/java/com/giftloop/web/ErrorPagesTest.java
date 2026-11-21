package com.giftloop.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Friendly 404 for unmatched URLs, and bare statuses for everything that isn't a page load.
 * Signed-in 403/404s from resources are covered in {@code GroupFlowTest}.
 */
@QuarkusTest
class ErrorPagesTest {

    private static final String NOT_FOUND = "We couldn't find that page";

    /** An unknown URL opened in a browser gets the styled 404 page. */
    @Test
    void unmatchedPageGetsFriendly404() {
        given().accept("text/html").when().get("/no-such-page")
                .then().statusCode(404)
                .contentType(containsString("text/html"))
                .body(containsString(NOT_FOUND))
                .body(containsString("href=\"/assets/app.css\""));
    }

    /** Non-browser requests, API paths and assets keep the bare 404. */
    @Test
    void otherRequestsKeepBare404() {
        given().accept("*/*").when().get("/no-such-page")
                .then().statusCode(404).body(not(containsString(NOT_FOUND)));
        given().accept("text/html").when().get("/api/no-such-thing")
                .then().statusCode(404).body(emptyString());
        given().accept("text/html").when().get("/assets/no-such-file.css")
                .then().statusCode(404).body(not(containsString(NOT_FOUND)));
    }

    /** Only HTML page requests outside the raw prefixes qualify. */
    @Test
    void wantsHtmlChecksAcceptAndPath() {
        String browser = "text/html,application/xhtml+xml,*/*;q=0.8";
        assertTrue(ErrorPages.wantsHtml("/groups/x", browser));
        assertFalse(ErrorPages.wantsHtml("/groups/x", null));
        assertFalse(ErrorPages.wantsHtml("/groups/x", "*/*"));
        assertFalse(ErrorPages.wantsHtml("/api/groups/x", browser));
        assertFalse(ErrorPages.wantsHtml("/assets/x.css", browser));
        assertFalse(ErrorPages.wantsHtml("/_static/x.js", browser));
        assertFalse(ErrorPages.wantsHtml("/q/dev-ui", browser));
    }
}
