package com.giftloop.legal;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Public legal pages and the footer that links them.
 */
@QuarkusTest
class LegalResourceTest {

    /**
     * Each document renders for anonymous visitors with its version date and the contact email.
     *
     * @param path page path
     */
    @ParameterizedTest
    @ValueSource(strings = {"/terms", "/privacy", "/cookies"})
    void legalPagesArePublic(String path) {
        given().when().get(path)
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Last updated November 17, 2026"))
                .body(containsString("mailto:privacy@example.com"));
    }

    /** Terms name the operator, minimum age and governing law. */
    @Test
    void termsStateOperatorAgeAndLaw() {
        given().when().get("/terms")
                .then().statusCode(200)
                .body(containsString("Colten Glover"))
                .body(containsString("at least 13"))
                .body(containsString("State of Indiana"));
    }

    /** The cookie notice lists every cookie the app sets. */
    @Test
    void cookieNoticeListsAllCookies() {
        given().when().get("/cookies")
                .then().statusCode(200)
                .body(containsString("<code>gl_at</code>"))
                .body(containsString("<code>gl_rt</code>"))
                .body(containsString("<code>gl_csrf</code>"));
    }

    /** Every page carries the legal footer. */
    @Test
    void footerLinksLegalPages() {
        given().when().get("/")
                .then().statusCode(200)
                .body(containsString("class=\"site-footer\""))
                .body(containsString("href=\"/terms\""))
                .body(containsString("href=\"/privacy\""))
                .body(containsString("href=\"/cookies\""));
    }
}
