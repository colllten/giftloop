package com.giftloop.security;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Security headers on pages, errors and static files. HSTS (off in tests): {@link SecurityHeadersHstsTest}.
 */
@QuarkusTest
class SecurityHeadersTest {

    /**
     * Asserts every header except HSTS.
     *
     * @param response response to check
     * @return the same response
     */
    static ValidatableResponse assertSecurityHeaders(ValidatableResponse response) {
        return response.header("Content-Security-Policy", is(SecurityHeaders.CSP))
                .header("X-Content-Type-Options", is("nosniff"))
                .header("X-Frame-Options", is("DENY"))
                .header("Referrer-Policy", is("same-origin"))
                .header("Permissions-Policy", is(SecurityHeaders.PERMISSIONS))
                .header("Cross-Origin-Opener-Policy", is("same-origin"));
    }

    /** Pages, the 404 fallback and static assets all carry the headers; HSTS is off in tests. */
    @Test
    void headersOnEveryKindOfResponse() {
        assertSecurityHeaders(given().get("/").then().statusCode(200)
                .header("Strict-Transport-Security", nullValue()));
        assertSecurityHeaders(given().accept("text/html").get("/no-such-page").then().statusCode(404));
        assertSecurityHeaders(given().get("/assets/app.css").then().statusCode(200));
    }
}
