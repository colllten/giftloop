package com.giftloop.group;

import com.giftloop.BrowserSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A freshly signed-up, onboarded user driving the real pages. Created once per test class
 * to stay under the local Supabase sign-up rate limit.
 *
 * @param browser     signed-in session
 * @param id          auth user id
 * @param displayName name shown to groups
 */
record TestUser(BrowserSession browser, UUID id, String displayName) {

    /**
     * Signs up and completes onboarding.
     *
     * @param displayName display name to use
     * @return the user
     */
    static TestUser create(String displayName) {
        BrowserSession browser = new BrowserSession();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        browser.signUp("grp-" + suffix + "@example.com", "long-enough-pw").then().header("HX-Redirect", "/");
        browser.get("/onboarding").then().statusCode(200);
        Map<String, String> fields = new HashMap<>();
        fields.put("username", "grp_" + suffix);
        fields.put("displayName", displayName);
        fields.put("birthMonth", "");
        fields.put("birthDay", "");
        fields.put("next", "/");
        browser.htmxMultipart("/onboarding", fields, null, null).then().header("HX-Redirect", "/");
        UUID id = UUID.fromString(browser.request().get("/api/me").path("id"));
        return new TestUser(browser, id, displayName);
    }
}
