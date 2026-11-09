package com.giftloop.auth;

import com.giftloop.BrowserSession;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;

/**
 * Password sign-up through the real pages against local Supabase.
 */
@QuarkusTest
class SignUpFlowTest {

    private static String newEmail() {
        return "su-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Sign-up page has email + new password and links back to sign-in, keeping next. */
    @Test
    void signUpPageLinksToSignIn() {
        new BrowserSession().get("/signup?next=/profile").then().statusCode(200)
                .body(containsString("Create account"))
                .body(containsString("autocomplete=\"new-password\""))
                .body(containsString("href=\"/signin?next=%2Fprofile\""));
    }

    /** Bad email and short passwords are rejected; a new account signs in and goes to onboarding. */
    @Test
    void signUpGoesToOnboarding() {
        BrowserSession browser = new BrowserSession();
        String email = newEmail();
        browser.get("/signup");
        browser.htmxPost("/signup", Map.of("email", "nope", "password", "long-enough-pw", "next", "/"))
                .then().body(containsString("Please enter a valid email address."));
        browser.htmxPost("/signup", Map.of("email", email, "password", "short", "next", "/"))
                .then().body(containsString("at least 8 characters"));
        browser.signUp(email, "long-enough-pw").then().statusCode(200).header("HX-Redirect", "/");

        browser.get("/").then().statusCode(303).header("Location", containsString("/onboarding"));
        browser.get("/profile").then().statusCode(303).header("Location", containsString("/onboarding?next=%2Fprofile"));
        browser.get("/onboarding").then().statusCode(200).body(containsString("Welcome to GiftLoop!"));
    }

    /** Signing up with an existing email is refused with a hint to sign in. */
    @Test
    void duplicateSignUpIsRefused() {
        new BrowserSession().signUp("bob@example.com", "long-enough-pw")
                .then().body(containsString("already an account"))
                .body(containsString("id=\"email-error\""));
    }

    /** Signed-in users visiting sign-up are sent on to next. */
    @Test
    void signedInUserSkipsSignUp() {
        BrowserSession browser = new BrowserSession();
        browser.signInWithPassword("bob@example.com", "password123");
        browser.get("/signup?next=/profile").then().statusCode(303).header("Location", containsString("/profile"));
    }
}
