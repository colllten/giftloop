package com.giftloop.share;

import com.giftloop.BrowserSession;
import com.giftloop.TestUser;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Share target / bookmarklet entry ({@code /add}) through the real pages. A local server
 * plays the store (the test profile lets link previews reach loopback).
 */
@QuarkusTest
class ShareFlowTest {

    private static HttpServer store;
    private static String product;

    @BeforeAll
    static void startStore() throws IOException {
        store = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        store.createContext("/lamp", ex -> {
            byte[] body = "<html><head><meta property=\"og:title\" content=\"Brass Desk Lamp\"></head></html>".getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        store.start();
        product = "http://127.0.0.1:" + store.getAddress().getPort() + "/lamp";
    }

    @AfterAll
    static void stopStore() {
        store.stop(0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Creates a group with one event per name and starts the user's wishlist on each. */
    private static UUID[] wishlists(TestUser user, String... events) {
        user.browser().get("/groups/new").then().statusCode(200);
        String group = user.browser().htmxPost("/groups", Map.of("name", "Share Group"))
                .then().statusCode(200).extract().header("HX-Redirect");
        UUID[] ids = new UUID[events.length];
        for (int i = 0; i < events.length; i++) {
            user.browser().get(group + "/events/new").then().statusCode(200);
            String event = user.browser().htmxPost(group + "/events", Map.of("name", events[i], "endDate", "2030-12-26"))
                    .then().statusCode(200).extract().header("HX-Redirect");
            user.browser().get(event).then().statusCode(200);
            String wishlist = user.browser().htmxPost(event + "/wishlist", Map.of())
                    .then().statusCode(200).extract().header("HX-Redirect");
            ids[i] = UUID.fromString(wishlist.substring("/wishlists/".length()));
        }
        return ids;
    }

    /** One open wishlist: straight there, with the add form filled from the link. */
    @Test
    void singleWishlistGoesStraightToFilledForm() {
        TestUser user = TestUser.create("Share Single");
        UUID id = wishlists(user, "Share Birthday")[0];
        String location = user.browser().request().queryParam("text", "Brass Desk Lamp " + product).get("/add")
                .then().statusCode(303).header("Location", containsString("/wishlists/" + id + "?add="))
                .extract().header("Location");
        user.browser().request().urlEncodingEnabled(false)
                .get(location.replaceFirst("^https?://[^/]+", "").replaceFirst("#.*$", ""))
                .then().statusCode(200)
                .body(containsString("id=\"add-gift\""))
                .body(containsString("value=\"Brass Desk Lamp\""))
                .body(containsString(product + "</textarea>"));
    }

    /** Several open wishlists: pick one. None: say how to start one. */
    @Test
    void pickerAndNoWishlist() {
        TestUser none = TestUser.create("Share None");
        none.browser().request().queryParam("url", product).get("/add").then().statusCode(200)
                .body(containsString("You don't have an open wishlist yet"));

        TestUser user = TestUser.create("Share Many");
        UUID[] ids = wishlists(user, "Share One", "Share Two");
        user.browser().request().queryParam("url", product).get("/add").then().statusCode(200)
                .body(containsString("Which wishlist should it go on?"))
                .body(containsString("Share One"))
                .body(containsString("/wishlists/" + ids[1] + "?add="));
    }

    /** No link: the help page explains; a bare visit shows the bookmarklet. */
    @Test
    void helpPage() {
        TestUser user = TestUser.create("Share Help");
        user.browser().request().queryParam("text", "no link here").get("/add").then().statusCode(200)
                .body(containsString("We couldn't find a link"));
        user.browser().request().get("/add").then().statusCode(200)
                .body(containsString("Add to GiftLoop"))
                .body(containsString("href=\"javascript:"))
                .body(containsString("add?url="));
    }

    /** Signed-out visitors sign in first and keep the shared link in next. */
    @Test
    void signedOutGoesToSignIn() {
        new BrowserSession().request().queryParam("url", product).get("/add").then()
                .statusCode(303).header("Location", containsString("/signin?next=" + encode("/add?url=")));
    }

    /** The web manifest declares the share target. */
    @Test
    void manifest() {
        new BrowserSession().request().get("/assets/manifest.webmanifest").then().statusCode(200)
                .body(containsString("\"share_target\""))
                .body(containsString("\"action\": \"/add\""));
        new BrowserSession().request().get("/assets/icons/icon-192.png").then().statusCode(200)
                .contentType(startsWith("image/png"));
    }
}
