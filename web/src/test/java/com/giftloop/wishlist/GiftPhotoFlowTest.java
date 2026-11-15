package com.giftloop.wishlist;

import com.giftloop.TestUser;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gift photos and link auto-fill through the real pages. A local server plays the store
 * (the test profile lets link previews reach loopback).
 * Cast: owner, viewer (member), other (member not picked for hidden gifts), outsider.
 */
@QuarkusTest
class GiftPhotoFlowTest {

    private static final Pattern IMAGE_DATA = Pattern.compile("name=\"image_data\" value=\"([^\"]+)\"");

    private static TestUser owner;
    private static TestUser viewer;
    private static TestUser other;
    private static TestUser outsider;
    private static UUID groupId;
    private static HttpServer store;
    private static String storeUrl;

    /** Lazily creates the users, a group with owner, viewer and other, and the fake store. */
    private static synchronized void setUp() {
        if (owner != null) {
            return;
        }
        owner = TestUser.create("Photo Owner");
        viewer = TestUser.create("Photo Viewer");
        other = TestUser.create("Photo Other");
        outsider = TestUser.create("Photo Outsider");
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", "Photo Family"))
                .then().statusCode(200).extract().header("HX-Redirect");
        groupId = UUID.fromString(location.substring("/groups/".length()));
        String code = owner.browser().request().get("/api/groups/" + groupId).path("joinCode");
        for (TestUser u : List.of(viewer, other)) {
            u.browser().get("/join").then().statusCode(200);
            u.browser().htmxPost("/join", Map.of("code", code)).then().statusCode(200);
        }
        startStore();
    }

    private static void startStore() {
        byte[] photo = png(800, 400);
        try {
            store = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        store.createContext("/mug", ex -> {
            byte[] body = """
                    <html><head><title>Camp Mug | Store</title>
                    <script type="application/ld+json">{"@type":"Product","name":"Enamel Camp Mug",
                      "image":"/mug.png","offers":{"price":"18.50","priceCurrency":"USD"}}</script>
                    </head></html>""".getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        store.createContext("/mug.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, photo.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(photo);
            }
        });
        store.start();
        storeUrl = "http://127.0.0.1:" + store.getAddress().getPort();
    }

    @AfterAll
    static void stopStore() {
        if (store != null) {
            store.stop(0);
        }
    }

    private static byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static UUID createWishlist(String eventName) {
        setUp();
        owner.browser().get("/groups/" + groupId + "/events/new").then().statusCode(200);
        String event = owner.browser()
                .htmxPost("/groups/" + groupId + "/events", Map.of("name", eventName, "endDate", "2026-12-26"))
                .then().statusCode(200).extract().header("HX-Redirect");
        UUID eventId = UUID.fromString(event.substring("/events/".length()));
        owner.browser().get("/events/" + eventId).then().statusCode(200);
        String location = owner.browser().htmxPost("/events/" + eventId + "/wishlist", Map.of())
                .then().statusCode(200).extract().header("HX-Redirect");
        UUID id = UUID.fromString(location.substring("/wishlists/".length()));
        owner.browser().get("/wishlists/" + id).then().statusCode(200);
        return id;
    }

    private static Map<String, Object> fields(String name) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", name);
        form.put("price", "");
        form.put("quantity", "1");
        form.put("links", "");
        form.put("notes", "");
        form.put("visibility", GiftForm.EVERYONE);
        return form;
    }

    private static String imageUrl(UUID wishlistId, String name) {
        return owner.browser().request().get("/api/wishlists/" + wishlistId)
                .path("gifts.find { it.name == '" + name + "' }.imageUrl");
    }

    private static UUID giftId(UUID wishlistId, String name) {
        return UUID.fromString(owner.browser().request().get("/api/wishlists/" + wishlistId)
                .path("gifts.find { it.name == '" + name + "' }.id"));
    }

    /** Pasting a link fills name, price, link and photo; saving stores the photo for everyone in the group. */
    @Test
    void autofillThenSave() {
        UUID wishlistId = createWishlist("Autofill Event");
        String base = "/wishlists/" + wishlistId + "/gifts";
        Map<String, Object> form = fields("");
        form.put("source_url", storeUrl + "/mug");
        String html = owner.browser().htmxMultipart(base + "/autofill", form, "photo", null, null)
                .then().statusCode(200)
                .body(containsString("value=\"Enamel Camp Mug\""))
                .body(containsString("value=\"18.50\""))
                .body(containsString(storeUrl + "/mug</textarea>"))
                .body(containsString("Filled in from 127.0.0.1"))
                .body(containsString("data:image/jpeg;base64,"))
                .extract().asString();
        Matcher m = IMAGE_DATA.matcher(html);
        assertTrue(m.find(), "photo staged in the form");

        Map<String, Object> save = fields("Enamel Camp Mug");
        save.put("price", "18.50");
        save.put("links", storeUrl + "/mug");
        save.put("image_data", m.group(1));
        owner.browser().htmxMultipart(base, save, "photo", null, null)
                .then().statusCode(200).body(containsString("Gift added."))
                .body(containsString("class=\"gift-photo\""));
        String url = imageUrl(wishlistId, "Enamel Camp Mug");
        assertNotNull(url);
        owner.browser().request().get(url).then().statusCode(200).contentType("image/jpeg");
        viewer.browser().request().get(url).then().statusCode(200);
        outsider.browser().request().get(url).then().statusCode(404);
    }

    /** Auto-fill never overwrites what the owner typed, and reports bad links. */
    @Test
    void autofillKeepsTypedValuesAndReportsProblems() {
        UUID wishlistId = createWishlist("Autofill Keep Event");
        String path = "/wishlists/" + wishlistId + "/gifts/autofill";
        Map<String, Object> form = fields("My own name");
        form.put("price", "12");
        form.put("source_url", storeUrl + "/mug");
        owner.browser().htmxMultipart(path, form, "photo", null, null)
                .then().statusCode(200)
                .body(containsString("value=\"My own name\""))
                .body(containsString("value=\"12\""))
                .body(not(containsString("Enamel Camp Mug")));

        form.put("source_url", "");
        owner.browser().htmxMultipart(path, form, "photo", null, null)
                .then().body(containsString("Paste a link to a product page."));
        form.put("source_url", "javascript:alert(1)");
        owner.browser().htmxMultipart(path, form, "photo", null, null)
                .then().body(containsString("Please paste a web address"));
        form.put("source_url", "http://10.0.0.1/admin");
        owner.browser().htmxMultipart(path, form, "photo", null, null)
                .then().statusCode(200)
                .body(containsString("We couldn&#39;t read details from that site"))
                .body(containsString("http://10.0.0.1/admin</textarea>"));

        viewer.browser().get("/wishlists/" + wishlistId).then().statusCode(200);
        viewer.browser().htmxMultipart(path, form, "photo", null, null).then().statusCode(403);
    }

    /** Owners upload, replace and remove photos; removed files are deleted from storage. */
    @Test
    void uploadAndRemovePhoto() {
        UUID wishlistId = createWishlist("Upload Event");
        String base = "/wishlists/" + wishlistId + "/gifts";
        owner.browser().htmxMultipart(base, fields("Uploaded"), "photo", "GIF89a....".getBytes(), "image/gif")
                .then().statusCode(200).body(containsString("Please choose a JPEG, PNG or WebP photo."))
                .body(containsString("value=\"Uploaded\""));
        owner.browser().htmxMultipart(base, fields("Uploaded"), "photo", png(300, 300), "image/png")
                .then().statusCode(200).body(containsString("Gift added."));
        String first = imageUrl(wishlistId, "Uploaded");
        assertNotNull(first);
        UUID giftId = giftId(wishlistId, "Uploaded");

        owner.browser().get("/wishlists/" + wishlistId + "/gifts/" + giftId + "/edit").then().statusCode(200)
                .body(containsString("Remove photo")).body(containsString(first));
        owner.browser().htmxMultipart(base + "/" + giftId, fields("Uploaded"), "photo", png(200, 100), "image/png")
                .then().statusCode(200).body(containsString("Gift saved."));
        String second = imageUrl(wishlistId, "Uploaded");
        assertNotNull(second);
        assertTrue(!second.equals(first), "replacing gives a new URL");
        owner.browser().request().get(first).then().statusCode(404);

        Map<String, Object> remove = fields("Uploaded");
        remove.put("remove_image", "true");
        owner.browser().htmxMultipart(base + "/" + giftId, remove, "photo", null, null)
                .then().statusCode(200).body(containsString("Gift saved."));
        assertNull(imageUrl(wishlistId, "Uploaded"));
        owner.browser().request().get(second).then().statusCode(404);
    }

    /** A hidden gift's photo is only served to the people who can see the gift. */
    @Test
    void hiddenGiftPhoto() {
        UUID wishlistId = createWishlist("Hidden Photo Event");
        Map<String, Object> form = fields("Hidden Photo");
        form.put("visibility", GiftForm.SELECTED);
        form.put("viewers", List.of(viewer.id().toString()));
        owner.browser().htmxMultipart("/wishlists/" + wishlistId + "/gifts", form, "photo", png(100, 100), "image/png")
                .then().statusCode(200).body(containsString("Shared with 1 person"));
        String url = imageUrl(wishlistId, "Hidden Photo");
        viewer.browser().request().get(url).then().statusCode(200);
        other.browser().request().get(url).then().statusCode(404);
        owner.browser().request().get("/gift-images/" + owner.id() + "/" + UUID.randomUUID() + "/..%2Fx.jpg")
                .then().statusCode(404);
    }

    /** Deleting a gift deletes its photo file. */
    @Test
    void deletingGiftDeletesPhoto() {
        UUID wishlistId = createWishlist("Delete Photo Event");
        owner.browser().htmxMultipart("/wishlists/" + wishlistId + "/gifts", fields("Doomed"), "photo",
                png(100, 100), "image/png").then().statusCode(200);
        String url = imageUrl(wishlistId, "Doomed");
        UUID giftId = giftId(wishlistId, "Doomed");
        owner.browser().htmxPost("/wishlists/" + wishlistId + "/gifts/" + giftId + "/delete", Map.of())
                .then().statusCode(200).body(containsString("Gift removed."));
        assertEquals(404, owner.browser().request().get(url).statusCode());
    }
}
