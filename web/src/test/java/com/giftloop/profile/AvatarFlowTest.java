package com.giftloop.profile;

import com.giftloop.BrowserSession;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Avatar upload, privacy and removal using seed users: Carol (owner), Bob
 * (shares a group with Carol) and Dave (no shared group).
 */
@QuarkusTest
class AvatarFlowTest {

    private static final String CAROL_ID = "00000000-0000-0000-0000-00000000ca01";

    private static Map<String, String> carolFields() {
        return Map.of("username", "carol", "displayName", "Carol", "birthMonth", "", "birthDay", "");
    }

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    /** Upload, view as owner/co-member/outsider, then remove. */
    @Test
    void avatarLifecycle() throws Exception {
        BrowserSession carol = new BrowserSession();
        carol.signInWithPassword("carol@example.com", "password123");
        carol.get("/profile").then().statusCode(200);

        carol.htmxMultipart("/profile", carolFields(), "hello".getBytes(), "text/plain")
                .then().body(containsString("Please choose a JPEG or PNG photo."));

        carol.htmxMultipart("/profile", carolFields(), png(600, 400), "image/png")
                .then().body(containsString("Your profile is saved."));
        String url = carol.request().get("/api/me").jsonPath().getString("avatarUrl");
        assertNotNull(url);

        Response image = carol.request().get(url);
        image.then().statusCode(200).contentType("image/jpeg").header("Cache-Control", containsString("private"));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.asByteArray()));
        assertEquals(256, decoded.getWidth());
        assertEquals(256, decoded.getHeight());

        BrowserSession bob = new BrowserSession();
        bob.signInWithPassword("bob@example.com", "password123");
        bob.request().get("/avatars/" + CAROL_ID).then().statusCode(200);

        BrowserSession dave = new BrowserSession();
        dave.signInWithPassword("dave@example.com", "password123");
        dave.request().get("/avatars/" + CAROL_ID).then().statusCode(404);

        carol.get("/profile");
        carol.htmxPost("/profile/avatar/remove", Map.of())
                .then().body(containsString("Your photo was removed."));
        carol.request().get("/api/me").then().body("avatarUrl", is(nullValue()));
        bob.request().get("/avatars/" + CAROL_ID).then().statusCode(404);
    }
}
