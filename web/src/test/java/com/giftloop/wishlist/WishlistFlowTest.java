package com.giftloop.wishlist;

import com.giftloop.TestUser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Events, wishlists and per-gift visibility through the real pages.
 * Cast: owner, viewer (picked for restricted gifts), other (a member who isn't), outsider.
 * Users and the group are shared to limit sign-ups; each test makes its own event.
 */
@QuarkusTest
class WishlistFlowTest {

    private static TestUser owner;
    private static TestUser viewer;
    private static TestUser other;
    private static TestUser outsider;
    private static UUID groupId;

    /** Lazily creates the shared users and a group with owner, viewer and other. */
    private static synchronized void setUp() {
        if (owner != null) {
            return;
        }
        owner = TestUser.create("Wish Owner");
        viewer = TestUser.create("Wish Viewer");
        other = TestUser.create("Wish Other");
        outsider = TestUser.create("Wish Outsider");
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", "Wish Family"))
                .then().statusCode(200).extract().header("HX-Redirect");
        groupId = UUID.fromString(location.substring("/groups/".length()));
        String code = owner.browser().request().get("/api/groups/" + groupId).path("joinCode");
        for (TestUser u : List.of(viewer, other)) {
            u.browser().get("/join").then().statusCode(200);
            u.browser().htmxPost("/join", Map.of("code", code)).then().header("HX-Redirect", "/groups/" + groupId);
        }
    }

    private static UUID createEvent(String name) {
        setUp();
        owner.browser().get("/groups/" + groupId + "/events/new").then().statusCode(200);
        String location = owner.browser()
                .htmxPost("/groups/" + groupId + "/events", Map.of("name", name, "endDate", "2026-12-26"))
                .then().statusCode(200).extract().header("HX-Redirect");
        return UUID.fromString(location.substring("/events/".length()));
    }

    private static UUID createWishlist(TestUser user, UUID eventId) {
        user.browser().get("/events/" + eventId).then().statusCode(200);
        String location = user.browser().htmxPost("/events/" + eventId + "/wishlist", Map.of())
                .then().statusCode(200).extract().header("HX-Redirect");
        return UUID.fromString(location.substring("/wishlists/".length()));
    }

    private static Map<String, Object> gift(String name, List<UUID> viewers) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", name);
        form.put("price", "25");
        form.put("quantity", "1");
        form.put("links", "shop.example/" + name.replace(' ', '-'));
        form.put("notes", "");
        form.put("visibility", viewers == null ? GiftForm.EVERYONE : GiftForm.SELECTED);
        if (viewers != null) {
            form.put("viewers", viewers.stream().map(UUID::toString).toList());
        }
        return form;
    }

    private static UUID giftId(UUID wishlistId, String name) {
        return UUID.fromString(owner.browser().request().get("/api/wishlists/" + wishlistId)
                .path("gifts.find { it.name == '" + name + "' }.id"));
    }

    private static String wishlistPath(UUID wishlistId) {
        return "/wishlists/" + wishlistId;
    }

    /** Events are created from the group page; each member gets one wishlist per event. */
    @Test
    void eventAndWishlistCreation() {
        setUp();
        owner.browser().get("/groups/" + groupId + "/events/new").then().statusCode(200);
        owner.browser().htmxPost("/groups/" + groupId + "/events", Map.of("name", " ", "endDate", "soon"))
                .then().statusCode(200)
                .body(containsString("Please give the event a name."))
                .body(containsString("Please enter a date like 2026-12-25."));
        outsider.browser().get("/groups/" + groupId + "/events/new").then().statusCode(404);

        UUID eventId = createEvent("Creation Event");
        viewer.browser().get("/groups/" + groupId).then().statusCode(200)
                .body(containsString("Creation Event"))
                .body(containsString("Ends Dec 26, 2026"));
        viewer.browser().get("/events/" + eventId).then().statusCode(200)
                .body(containsString("Start your wishlist"));
        outsider.browser().get("/events/" + eventId).then().statusCode(404);
        outsider.browser().htmxPost("/events/" + eventId + "/wishlist", Map.of()).then().statusCode(403);

        UUID first = createWishlist(viewer, eventId);
        assertEquals(first, createWishlist(viewer, eventId), "starting twice opens the same wishlist");
        viewer.browser().get("/events/" + eventId).then().statusCode(200)
                .body(containsString("Your wishlist"))
                .body(not(containsString("Start your wishlist")));
        other.browser().get("/events/" + eventId).then().body(containsString("Wish Viewer"));
        viewer.browser().get(wishlistPath(first)).then().statusCode(200)
                .body(containsString("Your wishlist is empty"));
    }

    /** Restricted gifts are seen only by the owner and picked viewers, until shared with everyone. */
    @Test
    void restrictedGiftVisibility() {
        UUID eventId = createEvent("Visibility Event");
        UUID wishlistId = createWishlist(owner, eventId);
        owner.browser().get(wishlistPath(wishlistId)).then().statusCode(200);
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts", gift("Open Gift", null))
                .then().statusCode(200).header("HX-Retarget", "#wishlist-body").body(containsString("Gift added."));
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts", gift("Secret Gift", List.of(viewer.id())))
                .then().statusCode(200).body(containsString("Shared with 1 person"))
                .body(containsString("Visible to Wish Viewer"))
                .body(containsString("href=\"https://shop.example/Secret-Gift\""));
        UUID secret = giftId(wishlistId, "Secret Gift");
        UUID open = giftId(wishlistId, "Open Gift");

        viewer.browser().get(wishlistPath(wishlistId)).then().statusCode(200)
                .body(containsString("Secret Gift"))
                .body(not(containsString("Shared with")));
        other.browser().get(wishlistPath(wishlistId)).then().statusCode(200)
                .body(containsString("Open Gift"))
                .body(not(containsString("Secret Gift")));
        other.browser().get(wishlistPath(wishlistId) + "/gifts/" + secret + "/edit").then().statusCode(404);
        other.browser().get(wishlistPath(wishlistId) + "/gifts/" + open + "/edit").then().statusCode(403);
        other.browser().htmxPost(wishlistPath(wishlistId) + "/gifts/" + secret, gift("Hacked", null))
                .then().statusCode(403);
        other.browser().htmxPost(wishlistPath(wishlistId) + "/gifts", gift("Sneaky", null)).then().statusCode(403);
        outsider.browser().get(wishlistPath(wishlistId)).then().statusCode(404);

        other.browser().request().get("/api/wishlists/" + wishlistId).then().statusCode(200)
                .body("gifts", hasSize(1))
                .body("gifts[0].visibleToAll", nullValue())
                .body("gifts[0].viewerIds", nullValue());
        owner.browser().request().get("/api/wishlists/" + wishlistId).then()
                .body("gifts.find { it.name == 'Secret Gift' }.visibleToAll", is(false))
                .body("gifts.find { it.name == 'Secret Gift' }.viewerIds", hasItem(viewer.id().toString()));

        owner.browser().get(wishlistPath(wishlistId) + "/gifts/" + secret + "/edit").then().statusCode(200)
                .body(containsString("Save gift"));
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts/" + secret, gift("Secret Gift", null))
                .then().statusCode(200).body(containsString("Gift saved."));
        other.browser().get(wishlistPath(wishlistId)).then().body(containsString("Secret Gift"));
    }

    /** Invalid gifts re-render the form; viewers outside the group are rejected. */
    @Test
    void giftValidation() {
        UUID eventId = createEvent("Validation Event");
        UUID wishlistId = createWishlist(owner, eventId);
        owner.browser().get(wishlistPath(wishlistId)).then().statusCode(200);
        Map<String, Object> noViewers = gift("Nobody", List.of());
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts", noViewers).then().statusCode(200)
                .header("HX-Retarget", nullValue())
                .body(containsString("Pick at least one person, or share with everyone."));
        Map<String, Object> badLink = gift("Bad Link", null);
        badLink.put("links", "javascript:alert(1)");
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts", badLink).then().statusCode(200)
                .body(containsString("Please enter web addresses, like example.com/gift."));
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts", gift("Outsider Gift", List.of(outsider.id())))
                .then().statusCode(400);
        owner.browser().request().get("/api/wishlists/" + wishlistId).then().body("gifts", hasSize(0));
    }

    /** Owners remove gifts and delete their wishlist; others cannot. */
    @Test
    void deleteGiftAndWishlist() {
        UUID eventId = createEvent("Delete Event");
        UUID wishlistId = createWishlist(owner, eventId);
        owner.browser().get(wishlistPath(wishlistId)).then().statusCode(200);
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts", gift("Doomed", null)).then().statusCode(200);
        UUID doomed = giftId(wishlistId, "Doomed");

        other.browser().get(wishlistPath(wishlistId)).then().statusCode(200);
        other.browser().htmxPost(wishlistPath(wishlistId) + "/gifts/" + doomed + "/delete", Map.of())
                .then().statusCode(403);
        other.browser().htmxPost(wishlistPath(wishlistId) + "/delete", Map.of()).then().statusCode(403);

        owner.browser().htmxPost(wishlistPath(wishlistId) + "/gifts/" + doomed + "/delete", Map.of())
                .then().statusCode(200).body(containsString("Gift removed."));
        owner.browser().htmxPost(wishlistPath(wishlistId) + "/delete", Map.of())
                .then().statusCode(200).header("HX-Redirect", "/events/" + eventId);
        owner.browser().get(wishlistPath(wishlistId)).then().statusCode(404);
    }
}
