package com.giftloop.profile;

import com.giftloop.BrowserSession;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Onboarding and profile editing through the real pages.
 */
@QuarkusTest
class OnboardingFlowTest {

    private static Map<String, String> fields(String username, String displayName, String month, String day) {
        Map<String, String> f = new HashMap<>();
        f.put("username", username);
        f.put("displayName", displayName);
        f.put("birthMonth", month);
        f.put("birthDay", day);
        f.put("next", "/");
        return f;
    }

    private static BrowserSession newUser() {
        BrowserSession browser = new BrowserSession();
        String email = "ob-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        browser.get("/signin");
        browser.htmxPost("/signup/password", Map.of("email", email, "password", "long-enough-pw", "next", "/"))
                .then().header("HX-Redirect", "/");
        browser.get("/onboarding").then().statusCode(200);
        return browser;
    }

    /** Validation errors are shown inline and nothing is saved. */
    @Test
    void onboardingValidation() {
        BrowserSession browser = newUser();
        browser.htmxMultipart("/onboarding", fields("ok_name", "", "", ""), null, null)
                .then().body(containsString("Please enter the name your group will see."));
        browser.htmxMultipart("/onboarding", fields("no spaces", "Pat", "", ""), null, null)
                .then().body(containsString("letters, numbers or underscores"));
        browser.htmxMultipart("/onboarding", fields("BOB", "Pat", "", ""), null, null)
                .then().body(containsString("That username is taken."));
        browser.htmxMultipart("/onboarding", fields("ok_name", "Pat", "2", "30"), null, null)
                .then().body(containsString("That date doesn"));
        browser.htmxMultipart("/onboarding", fields("ok_name", "Pat", "5", ""), null, null)
                .then().body(containsString("both a month and a day"));
        browser.request().get("/api/me").then().body("onboarded", is(false));
    }

    /** Completing onboarding unlocks the app and saves all fields. */
    @Test
    void completeOnboardingThenEdit() {
        BrowserSession browser = newUser();
        String username = "u_" + UUID.randomUUID().toString().substring(0, 8);
        browser.htmxMultipart("/onboarding", fields(username, "Grandma Jo", "2", "29"), null, null)
                .then().statusCode(200).header("HX-Redirect", "/");

        browser.get("/").then().statusCode(200).body(containsString("Hi, Grandma Jo!"));
        browser.get("/onboarding").then().statusCode(303);
        browser.request().get("/api/me").then()
                .body("username", is(username))
                .body("displayName", is("Grandma Jo"))
                .body("birthMonth", is(2))
                .body("birthDay", is(29))
                .body("onboarded", is(true));

        browser.get("/profile").then().statusCode(200).body(containsString("Your profile"));
        String renamed = username.toUpperCase().replace("U_", "r_");
        browser.htmxMultipart("/profile", fields(renamed, "Jo", "", ""), null, null)
                .then().body(containsString("Your profile is saved."));
        browser.request().get("/api/me").then()
                .body("username", is(renamed))
                .body("displayName", is("Jo"))
                .body("birthMonth", is((Object) null));
    }

    /** Live username check: format, taken (any case), and own name. */
    @Test
    void usernameCheck() {
        BrowserSession browser = newUser();
        browser.request().get("/onboarding/username-check?username=x").then()
                .body(containsString("3 to 32 letters"));
        browser.request().get("/onboarding/username-check?username=Alice").then()
                .body(containsString("taken"));
        browser.request().get("/onboarding/username-check?username=free_" + UUID.randomUUID().toString().substring(0, 6))
                .then().body(containsString("is available"));
        String own = browser.request().get("/api/me").jsonPath().getString("username");
        browser.request().get("/onboarding/username-check?username=" + own).then()
                .body(containsString("is available"));
    }
}
