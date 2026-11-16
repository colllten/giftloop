package com.giftloop.legal;

import com.giftloop.BrowserSession;
import com.giftloop.TestUser;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Users who haven't accepted the current legal documents are sent to {@code /legal/accept}
 * before gated pages, and continue once they accept.
 */
@QuarkusTest
class LegalAcceptFlowTest {

    @Inject
    AgroalDataSource dataSource;

    /** Gate redirects, the checkbox is required, and accepting records the version and continues. */
    @Test
    void unacceptedUserMustAcceptBeforeContinuing() throws SQLException {
        TestUser user = TestUser.create("Legal Lee");
        assertEquals("2026-11-17", termsVersion(user.id()));
        clearAcceptance(user.id());

        user.browser().get("/profile").then().statusCode(303)
                .header("Location", containsString("/legal/accept?next=%2Fprofile"));
        user.browser().get("/legal/accept?next=/profile").then().statusCode(200)
                .body(containsString("Before you continue"))
                .body(containsString("name=\"acceptTerms\""));

        user.browser().htmxPost("/legal/accept", Map.of("next", "/profile"))
                .then().statusCode(200).header("HX-Redirect", nullValue())
                .body(containsString("Please tick the box to agree"));
        assertNull(termsVersion(user.id()));

        user.browser().htmxPost("/legal/accept", Map.of("acceptTerms", "true", "next", "/profile"))
                .then().statusCode(200).header("HX-Redirect", "/profile");
        assertEquals("2026-11-17", termsVersion(user.id()));
        user.browser().get("/profile").then().statusCode(200);
        user.browser().get("/legal/accept?next=/profile").then().statusCode(303)
                .header("Location", containsString("/profile"));
    }

    /** Anonymous visitors are sent to sign in. */
    @Test
    void acceptPageRequiresSignIn() {
        new BrowserSession().get("/legal/accept").then().statusCode(303)
                .header("Location", containsString("/signin"));
    }

    private void clearAcceptance(UUID userId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "update \"Profile\" set terms_version = null, terms_accepted_at = null where id = ?")) {
            s.setObject(1, userId);
            s.executeUpdate();
        }
    }

    private String termsVersion(UUID userId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement s = c.prepareStatement("select terms_version from \"Profile\" where id = ?")) {
            s.setObject(1, userId);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }
}
