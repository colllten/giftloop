package com.giftloop.auth;

import com.giftloop.BrowserSession;
import com.giftloop.Mailpit;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Email-first sign-in through the real pages against local Supabase.
 */
@QuarkusTest
class SignInFlowTest {

    private static String newEmail() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Valid email shows the three choices; invalid email shows an inline error. */
    @Test
    void emailStepShowsChoicesOrError() {
        BrowserSession browser = new BrowserSession();
        browser.get("/signin").then().statusCode(200).body(containsString("Email address"));
        browser.htmxPost("/signin/email", Map.of("email", "not-an-email", "next", "/"))
                .then().body(containsString("Please enter a valid email address."));
        browser.htmxPost("/signin/email", Map.of("email", " Bob@Example.com ", "next", "/"))
                .then().body(containsString("bob@example.com"))
                .body(containsString("Email me a sign-in code"))
                .body(containsString("Sign in with my password"))
                .body(containsString("Create an account with a password"));
    }

    /** Correct password signs in and sets HttpOnly session cookies. */
    @Test
    void passwordSignInSetsSessionCookies() {
        BrowserSession browser = new BrowserSession();
        Response response = browser.signInWithPassword("bob@example.com", "password123");
        response.then().statusCode(200).header("HX-Redirect", "/");
        String setCookies = String.join("\n", response.headers().getValues("Set-Cookie"));
        assertTrue(setCookies.contains("gl_at=") && setCookies.contains("gl_rt="), setCookies);
        assertTrue(setCookies.contains("HttpOnly") && setCookies.contains("SameSite=Lax"), setCookies);

        browser.get("/").then().statusCode(200).body(containsString("Hi, Bob!"));
    }

    /** Wrong password shows a friendly inline error and no session. */
    @Test
    void wrongPasswordShowsError() {
        BrowserSession browser = new BrowserSession();
        Response response = browser.signInWithPassword("bob@example.com", "wrong-password");
        response.then().statusCode(200)
                .body(containsString("email and password don"));
        assertEquals(null, response.getHeader("HX-Redirect"));
    }

    /** Short passwords are rejected; a new password account goes to onboarding. */
    @Test
    void passwordSignUpGoesToOnboarding() {
        BrowserSession browser = new BrowserSession();
        String email = newEmail();
        browser.get("/signin");
        browser.htmxPost("/signup/password", Map.of("email", email, "password", "short", "next", "/"))
                .then().body(containsString("at least 8 characters"));
        browser.htmxPost("/signup/password", Map.of("email", email, "password", "long-enough-pw", "next", "/"))
                .then().statusCode(200).header("HX-Redirect", "/");

        browser.get("/").then().statusCode(303).header("Location", containsString("/onboarding"));
        browser.get("/profile").then().statusCode(303).header("Location", containsString("/onboarding?next=%2Fprofile"));
        browser.get("/onboarding").then().statusCode(200).body(containsString("Welcome to GiftLoop!"));
    }

    /** Signing up again with an existing email is refused with a hint to sign in. */
    @Test
    void duplicatePasswordSignUpIsRefused() {
        BrowserSession browser = new BrowserSession();
        browser.get("/signin");
        browser.htmxPost("/signup/password", Map.of("email", "bob@example.com", "password", "long-enough-pw", "next", "/"))
                .then().body(containsString("already an account"));
    }

    /** Emailed code: wrong code is rejected, the real code signs in (and creates the account). */
    @Test
    void codeSignInCreatesAccount() {
        BrowserSession browser = new BrowserSession();
        String email = newEmail();
        browser.get("/signin");
        browser.htmxPost("/signin/code", Map.of("email", email, "next", "/profile"))
                .then().body(containsString("We sent a 6-digit code"));
        browser.htmxPost("/signin/code/verify", Map.of("email", email, "code", "12", "next", "/profile"))
                .then().body(containsString("Please enter the 6-digit code"));

        String code = Mailpit.latestCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        browser.htmxPost("/signin/code/verify", Map.of("email", email, "code", wrong, "next", "/profile"))
                .then().body(containsString("wrong or has expired"));

        browser.htmxPost("/signin/code/verify", Map.of("email", email, "code", code, "next", "/profile"))
                .then().statusCode(200).header("HX-Redirect", "/profile");
        browser.get("/profile").then().statusCode(303).header("Location", containsString("/onboarding"));
    }

    /** Form posts without a CSRF token are rejected. */
    @Test
    void postWithoutCsrfIsRejected() {
        given().header("HX-Request", "true").formParam("email", "bob@example.com")
                .post("/signin/email").then().statusCode(400);
    }

    /** Unsafe next targets are replaced with "/". */
    @Test
    void openRedirectIsBlocked() {
        given().get("/signin?next=//evil.example").then().statusCode(200)
                .body(containsString("name=\"next\" value=\"/\""))
                .body(not(containsString("evil.example")));
    }

    /** Signed-out pages that need a session redirect to sign-in and come back. */
    @Test
    void protectedPageRedirectsToSignIn() {
        given().redirects().follow(false).get("/profile")
                .then().statusCode(303).header("Location", containsString("/signin?next=%2Fprofile"));
        given().header("HX-Request", "true").header("HX-Current-URL", "http://localhost/profile")
                .get("/onboarding/username-check?username=abc")
                .then().header("HX-Redirect", containsString("/signin?next=%2Fprofile"));
    }

    /** A missing access cookie is restored from the refresh cookie. */
    @Test
    void refreshCookieRestoresSession() {
        Response signIn = new BrowserSession().signInWithPassword("bob@example.com", "password123");
        String refresh = signIn.getDetailedCookie("gl_rt").getValue();
        assertNotNull(refresh);

        Response page = given().redirects().follow(false).cookie("gl_rt", refresh).get("/profile");
        page.then().statusCode(200).body(containsString("Your profile"));
        assertNotNull(page.getDetailedCookie("gl_at"), "new access cookie issued");

        // Garbage access token + valid refresh token is also recovered.
        String rotated = page.getDetailedCookie("gl_rt").getValue();
        given().redirects().follow(false).cookie("gl_at", "garbage").cookie("gl_rt", rotated).get("/profile")
                .then().statusCode(200);
    }

    /** A bad access cookie without a refresh cookie is cleared and the user is sent to sign in. */
    @Test
    void badCookieWithoutRefreshSignsOut() {
        Response response = given().redirects().follow(false).cookie("gl_at", "garbage").get("/profile");
        response.then().statusCode(303).header("Location", containsString("/signin"));
        assertEquals(0, response.getDetailedCookie("gl_at").getMaxAge());
    }

    /** Sign-out clears cookies and revokes the refresh token. */
    @Test
    void signOutRevokesSession() {
        BrowserSession browser = new BrowserSession();
        Response signIn = browser.signInWithPassword("bob@example.com", "password123");
        String refresh = signIn.getDetailedCookie("gl_rt").getValue();

        Response out = browser.request().formParam("csrf_token", browser.csrfToken()).post("/signout");
        out.then().statusCode(303).header("Location", containsString("/"));
        assertEquals(0, out.getDetailedCookie("gl_rt").getMaxAge());

        given().redirects().follow(false).cookie("gl_rt", refresh).get("/profile")
                .then().statusCode(303).header("Location", containsString("/signin"));
    }
}
