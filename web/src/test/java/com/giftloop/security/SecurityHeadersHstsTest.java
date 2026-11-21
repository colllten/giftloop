package com.giftloop.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * With HSTS on (the prod default), Strict-Transport-Security is sent with the other headers.
 */
@QuarkusTest
@TestProfile(SecurityHeadersHstsTest.HstsOn.class)
class SecurityHeadersHstsTest {

    /** HSTS is sent alongside the rest. */
    @Test
    void hstsSent() {
        SecurityHeadersTest.assertSecurityHeaders(given().get("/").then().statusCode(200))
                .header("Strict-Transport-Security", is(SecurityHeaders.HSTS));
    }

    /** Test profile enabling HSTS. */
    public static class HstsOn implements QuarkusTestProfile {
        /**
         * Overrides the test-profile default.
         *
         * @return config overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("giftloop.security.hsts", "true");
        }
    }
}
