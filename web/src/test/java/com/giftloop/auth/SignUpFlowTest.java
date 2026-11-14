package com.giftloop.auth;

import com.giftloop.BrowserSession;
import com.giftloop.Mailpit;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Code-based sign-up through the real pages against local Supabase.
 */
@QuarkusTest
class SignUpFlowTest {

    private static String newEmail() {
        return "su-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** The code step with the email (which varies per request) blanked out. */
    private static String codeStepFor(String email) {
        return new BrowserSession().requestSignUpCode(email).then().statusCode(200).extract().asString()
                .replace(email, "EMAIL")
                .replaceAll("name=\"csrf_token\" value=\"[^\"]+\"", "CSRF");
    }

    /** Sign-up page asks for an email and links back to sign-in, keeping next. */
    @Test
    void signUpPageLinksToSignIn() {
        new BrowserSession().get("/signup?next=/profile").then().statusCode(200)
                .body(containsString("Email me a code"))
                .body(containsString("href=\"/signin?next=%2Fprofile\""));
    }

    /** Bad input is rejected; a new account signs in and goes to onboarding. */
    @Test
    void signUpGoesToOnboarding() {
        BrowserSession browser = new BrowserSession();
        String email = newEmail();
        browser.get("/signup");
        browser.htmxPost("/signup", Map.of("email", "nope", "next", "/"))
                .then().body(containsString("Please enter a valid email address."));
        browser.requestSignUpCode(email).then().body(containsString("We sent a 6-digit code"))
                .body(containsString("autocomplete=\"new-password\""));
        String code = Mailpit.latestCode(email);
        browser.htmxPost("/signup/verify", Map.of("email", email, "code", "12", "password", "long-enough-pw", "next", "/"))
                .then().body(containsString("Please enter the 6-digit code"));
        browser.htmxPost("/signup/verify", Map.of("email", email, "code", code, "password", "short", "next", "/"))
                .then().body(containsString("at least 8 characters"));
        String wrong = code.equals("000000") ? "111111" : "000000";
        browser.htmxPost("/signup/verify", Map.of("email", email, "code", wrong, "password", "long-enough-pw", "next", "/"))
                .then().body(containsString("wrong or has expired"));
        browser.htmxPost("/signup/verify", Map.of("email", email, "code", code, "password", "long-enough-pw", "next", "/"))
                .then().statusCode(200).header("HX-Redirect", "/");

        browser.get("/").then().statusCode(303).header("Location", containsString("/onboarding"));
        browser.get("/onboarding").then().statusCode(200).body(containsString("Welcome to GiftLoop!"));
        new BrowserSession().signInWithPassword(email, "long-enough-pw").then().header("HX-Redirect", "/");
    }

    /** An existing email gets the exact same response as a new one, never an "already registered" hint. */
    @Test
    void existingEmailLooksLikeNewEmail() {
        String existing = codeStepFor("bob@example.com");
        String fresh = codeStepFor(newEmail());
        assertEquals(fresh, existing);
        new BrowserSession().requestSignUpCode("bob@example.com")
                .then().body(not(containsString("already")));
    }

    /** Signing up with an existing email signs into that account with the newly chosen password. */
    @Test
    void existingAccountSignsInWithNewPassword() {
        String email = newEmail();
        new BrowserSession().signUp(email, "first-password").then().header("HX-Redirect", "/");

        new BrowserSession().signUp(email, "second-password").then().statusCode(200).header("HX-Redirect", "/");
        new BrowserSession().signInWithPassword(email, "first-password").then().body(containsString("don"));
        new BrowserSession().signInWithPassword(email, "second-password").then().header("HX-Redirect", "/");
    }

    /** A password set by someone who pre-registered the email directly with Supabase is replaced. */
    @Test
    void preRegisteredPasswordIsReplaced() throws InterruptedException {
        String email = newEmail();
        var config = ConfigProvider.getConfig();
        given().baseUri(config.getValue("supabase.url", String.class))
                .header("apikey", config.getValue("supabase.publishable-key", String.class))
                .contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", "attacker-password"))
                .post("/auth/v1/signup").then().statusCode(200);
        Mailpit.latestCode(email); // discard the confirmation email so the next code is ours
        Thread.sleep(1100); // Supabase allows one email per address per second (max_frequency)

        BrowserSession victim = new BrowserSession();
        victim.requestSignUpCode(email);
        String code = Mailpit.latestCode(email);
        victim.htmxPost("/signup/verify", Map.of("email", email, "code", code, "password", "victim-password", "next", "/"))
                .then().header("HX-Redirect", "/");

        new BrowserSession().signInWithPassword(email, "attacker-password").then().body(containsString("don"));
        new BrowserSession().signInWithPassword(email, "victim-password").then().header("HX-Redirect", "/");
    }

    /** Signed-in users visiting sign-up are sent on to next. */
    @Test
    void signedInUserSkipsSignUp() {
        BrowserSession browser = new BrowserSession();
        browser.signInWithPassword("bob@example.com", "password123");
        browser.get("/signup?next=/profile").then().statusCode(303).header("Location", containsString("/profile"));
    }
}
