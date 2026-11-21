package com.giftloop.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unexpected exceptions become a 500 with a reference ID (page, HTMX fragment or JSON),
 * are counted, and never leak details; client errors and static files are untouched.
 */
@QuarkusTest
class UnhandledErrorMapperTest {

    private static final Pattern REFERENCE = Pattern.compile("<code>([0-9A-Z]{10})</code>");

    @Inject
    MeterRegistry registry;

    private double unhandled() {
        return registry.counter("giftloop.errors.unhandled", "exception", "IllegalStateException").count();
    }

    /** A failing page renders the error page with a reference ID and is counted. */
    @Test
    void pageShowsReference() {
        double before = unhandled();
        String html = given().when().get("/test-only/boom")
                .then().statusCode(500)
                .contentType(containsString("text/html"))
                .body(containsString("Something went wrong"))
                .body(not(containsString(ThrowingTestResource.SECRET)))
                .extract().asString();
        Matcher m = REFERENCE.matcher(html);
        assertTrue(m.find(), "reference ID shown");
        assertTrue(html.contains("<html"), "full page");
        assertEquals(before + 1, unhandled());
    }

    /** HTMX requests get just the fragment, retargeted into the page's main element. */
    @Test
    void htmxGetsFragment() {
        given().header("HX-Request", "true").when().get("/test-only/boom")
                .then().statusCode(500)
                .header("HX-Retarget", "main")
                .header("HX-Reswap", "innerHTML")
                .body(containsString("Something went wrong"))
                .body(not(containsString("<html")));
    }

    /** API callers get JSON with the reference ID. */
    @Test
    void apiGetsJson() {
        given().when().get("/api/test-only/boom")
                .then().statusCode(500)
                .body("error", org.hamcrest.Matchers.is("internal_error"))
                .body("errorId", matchesPattern("[0-9A-Z]{10}"))
                .body(not(containsString(ThrowingTestResource.SECRET)));
    }

    /** Unknown routes stay 404 and static files are still served. */
    @Test
    void clientErrorsAndStaticUntouched() {
        given().when().get("/no-such-page").then().statusCode(404);
        given().when().get("/assets/app.css").then().statusCode(200);
    }

    /** Reference IDs are 10 Crockford base32 characters. */
    @Test
    void errorIdFormat() {
        assertTrue(ErrorIds.next().matches("[0-9A-HJKMNP-TV-Z]{10}"));
    }
}
