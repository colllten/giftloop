package com.giftloop.auth;

import com.giftloop.BrowserSession;
import com.giftloop.Mailpit;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forgot password via emailed recovery code, against local Supabase and Mailpit.
 */
@QuarkusTest
class PasswordResetFlowTest {

    private static String newEmail() {
        return "pr-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Wrong code is rejected; the real code lets the user set a new password, and the old one stops working. */
    @Test
    void resetWithEmailedCode() {
        String email = newEmail();
        new BrowserSession().signUp(email, "old-password-1").then().header("HX-Redirect", "/");

        BrowserSession browser = new BrowserSession();
        browser.get("/signin/reset?next=/profile").then().statusCode(200)
                .body(containsString("Reset your password"));
        browser.htmxPost("/signin/reset", Map.of("email", email, "next", "/profile"))
                .then().body(containsString("we sent it a 6-digit code"));

        String code = Mailpit.latestCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        browser.htmxPost("/signin/reset/verify", Map.of("email", email, "code", wrong, "next", "/profile"))
                .then().body(containsString("wrong or has expired"));

        Response verified = browser.htmxPost("/signin/reset/verify",
                Map.of("email", email, "code", code, "next", "/profile"));
        verified.then().statusCode(200).body(containsString("New password"));
        String setCookies = String.join("\n", verified.headers().getValues("Set-Cookie"));
        assertTrue(setCookies.contains("gl_at="), setCookies);

        browser.htmxPost("/signin/reset/password", Map.of("password", "short", "next", "/profile"))
                .then().body(containsString("at least 8 characters"));
        browser.htmxPost("/signin/reset/password", Map.of("password", "new-password-2", "next", "/profile"))
                .then().statusCode(200).header("HX-Redirect", "/profile");

        new BrowserSession().signInWithPassword(email, "new-password-2").then().header("HX-Redirect", "/");
        assertEquals(null, new BrowserSession().signInWithPassword(email, "old-password-1").getHeader("HX-Redirect"));
    }

    /** Unknown emails get the same response as known ones. */
    @Test
    void unknownEmailLooksTheSame() {
        new BrowserSession().htmxPost("/signin/reset", Map.of("email", newEmail(), "next", "/"))
                .then().statusCode(200).body(containsString("we sent it a 6-digit code"));
    }

    /** Setting a password without a session sends the user to sign in. */
    @Test
    void setPasswordRequiresSession() {
        BrowserSession browser = new BrowserSession();
        String csrf = browser.csrfToken();
        browser.request().header("HX-Request", "true").header("HX-Current-URL", "http://localhost/signin/reset")
                .formParam("csrf_token", csrf).formParam("password", "whatever-123")
                .post("/signin/reset/password")
                .then().header("HX-Redirect", containsString("/signin"));
    }
}
