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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Off-list gifts through the real pages: a giver records something not on the list, others see
 * it as covered, the owner sees it only after the reveal, and only the giver edits or removes it.
 * Cast: owner (group and wishlist owner), giver, bystander (member). Each test makes its own event.
 */
@QuarkusTest
class OffListFlowTest {

    private static final String FUTURE_END = "2026-12-26";
    private static final String PAST_END = "2026-01-01";

    private static TestUser owner;
    private static TestUser giver;
    private static TestUser bystander;
    private static UUID groupId;

    /** Lazily creates the shared users and a group with all of them. */
    private static synchronized void setUp() {
        if (owner != null) {
            return;
        }
        owner = TestUser.create("Off Owner");
        giver = TestUser.create("Off Giver");
        bystander = TestUser.create("Off Bystander");
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", "Off-list Family"))
                .then().statusCode(200).extract().header("HX-Redirect");
        groupId = UUID.fromString(location.substring("/groups/".length()));
        String code = owner.browser().request().get("/api/groups/" + groupId).path("joinCode");
        for (TestUser u : List.of(giver, bystander)) {
            u.browser().get("/join").then().statusCode(200);
            u.browser().htmxPost("/join", Map.of("code", code)).then().header("HX-Redirect", "/groups/" + groupId);
        }
    }

    /** An event with the owner's wishlist and one gift on it; returns the wishlist id. */
    private static UUID ownerWishlist(String eventName, String endDate) {
        setUp();
        owner.browser().get("/groups/" + groupId + "/events/new").then().statusCode(200);
        String event = owner.browser()
                .htmxPost("/groups/" + groupId + "/events", Map.of("name", eventName, "endDate", endDate))
                .then().statusCode(200).extract().header("HX-Redirect");
        String wishlist = owner.browser().htmxPost(event + "/wishlist", Map.of())
                .then().statusCode(200).extract().header("HX-Redirect");
        UUID id = UUID.fromString(wishlist.substring("/wishlists/".length()));
        owner.browser().get(path(id)).then().statusCode(200);
        owner.browser().htmxPost(path(id) + "/gifts", Map.of("name", "Board game", "quantity", "1",
                "visibility", "everyone")).then().statusCode(200);
        return id;
    }

    /** Records an off-list gift as {@code user}; returns the raw response checks' starting point. */
    private static io.restassured.response.Response addOffList(TestUser user, UUID wishlistId, String name,
                                                               boolean bought, boolean anonymous) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", name);
        form.put("price", "25");
        form.put("links", "example.com/" + name.replace(' ', '-'));
        form.put("notes", "Red");
        if (bought) {
            form.put("bought", "on");
        }
        if (anonymous) {
            form.put("anonymous", "on");
        }
        user.browser().get(path(wishlistId)).then().statusCode(200);
        return user.browser().htmxPost(path(wishlistId) + "/off-list", form);
    }

    /** The off-list gift's id, as the giver sees it. */
    private static UUID giftId(UUID wishlistId, String name) {
        return UUID.fromString(giver.browser().request().get("/api/wishlists/" + wishlistId)
                .path("gifts.find { it.name == '" + name + "' }.id"));
    }

    private static String path(UUID wishlistId) {
        return "/wishlists/" + wishlistId;
    }

    /** A giver records, edits and removes an off-list gift; the owner never sees it before the reveal. */
    @Test
    void addEditAndRemove() {
        UUID w = ownerWishlist("Off-list Basics", FUTURE_END);

        giver.browser().get(path(w)).then().statusCode(200)
                .body(containsString("Giving something not on the list?"))
                .body(containsString("Off Owner won't see this until the event is over."));
        owner.browser().get(path(w)).then().statusCode(200)
                .body(not(containsString("Giving something not on the list?")));

        giver.browser().htmxPost(path(w) + "/off-list", Map.of("name", "", "price", "abc")).then().statusCode(200)
                .body(containsString("Please name the gift."))
                .body(containsString("Please enter a price like 24.99."));

        addOffList(giver, w, "Wool scarf", true, false).then().statusCode(200)
                .header("HX-Retarget", "#wishlist-body")
                .body(containsString("Others in the group can see it&#39;s covered; Off Owner can&#39;t."))
                .body(containsString("Not on the list"))
                .body(containsString("Wool scarf"))
                .body(containsString("You bought"))
                .body(containsString("Edit details"));
        UUID scarf = giftId(w, "Wool scarf");
        giver.browser().request().get("/api/wishlists/" + w).then()
                .body("gifts.find { it.name == 'Wool scarf' }.offList", is(true))
                .body("gifts.find { it.name == 'Wool scarf' }.links", hasItem("https://example.com/Wool-scarf"));
        giver.browser().request().get("/api/claims").then()
                .body("find { it.giftName == 'Wool scarf' }.offList", is(true))
                .body("find { it.giftName == 'Wool scarf' }.creator", is(true))
                .body("find { it.giftName == 'Wool scarf' }.purchased", is(1));
        giver.browser().get("/claims").then().statusCode(200).body(containsString("Not on the list"));

        // Others see it's covered but can't claim or edit it.
        bystander.browser().get(path(w)).then().statusCode(200)
                .body(containsString("Wool scarf"))
                .body(containsString("Off Giver claimed 1 · bought"));
        bystander.browser().htmxPost(path(w) + "/gifts/" + scarf + "/claims", Map.of("quantity", "1"))
                .then().statusCode(403);
        bystander.browser().get(path(w) + "/gifts/" + scarf + "/off-list/edit").then().statusCode(403);
        bystander.browser().htmxPost(path(w) + "/gifts/" + scarf + "/off-list", Map.of("name", "Hat"))
                .then().statusCode(403);

        // The owner sees nothing and can't add one to their own list.
        owner.browser().get(path(w)).then().statusCode(200)
                .body(not(containsString("Wool scarf")))
                .body(not(containsString("Not on your list")));
        owner.browser().request().get("/api/wishlists/" + w).then().body("gifts.size()", is(1));
        owner.browser().get(path(w) + "/gifts/" + scarf + "/off-list/edit").then().statusCode(404);
        owner.browser().htmxPost(path(w) + "/gifts/" + scarf + "/delete", Map.of()).then().statusCode(403);
        owner.browser().htmxPost(path(w) + "/off-list", Map.of("name", "Self gift")).then().statusCode(403);

        // The giver edits it.
        giver.browser().get(path(w) + "/gifts/" + scarf + "/off-list/edit").then().statusCode(200)
                .body(containsString("Save gift"))
                .body(containsString("value=\"Wool scarf\""));
        giver.browser().htmxPost(path(w) + "/gifts/" + scarf + "/off-list",
                        Map.of("name", "Red wool scarf", "price", "30", "links", "", "notes", ""))
                .then().statusCode(200)
                .body(containsString("Gift saved."))
                .body(containsString("Red wool scarf"));

        // Removing it (releasing the claim) deletes it and refreshes the whole body.
        UUID claimId = UUID.fromString(giver.browser().request().get("/api/claims")
                .path("find { it.giftId == '" + scarf + "' }.id"));
        giver.browser().htmxPost(path(w) + "/gifts/" + scarf + "/claims/" + claimId + "/delete", Map.of("view", ""))
                .then().statusCode(200)
                .header("HX-Retarget", "#wishlist-body")
                .body(containsString("Claim released."))
                .body(not(containsString("Red wool scarf")))
                .body(not(containsString("id=\"off-list-heading\"")));
        bystander.browser().get(path(w)).then().body(not(containsString("Red wool scarf")));
    }

    /** After the reveal the owner sees off-list gifts (anonymous givers stay hidden) but can't change or copy them. */
    @Test
    void ownerSeesAfterReveal() {
        UUID past = ownerWishlist("Off-list Last Year", PAST_END);
        giver.browser().get(path(past)).then()
                .body(containsString("Off Owner can see claims on this list, so they'll see this too."));
        addOffList(giver, past, "Fancy pen", false, true).then().statusCode(200)
                .body(containsString("Added. Everyone in the group can see it, including Off Owner."));
        UUID pen = giftId(past, "Fancy pen");

        owner.browser().get(path(past)).then().statusCode(200)
                .body(containsString("Not on your list"))
                .body(containsString("Fancy pen"))
                .body(containsString("Someone claimed 1"))
                .body(not(containsString("/gifts/" + pen + "/edit")))
                .body(not(containsString("/gifts/" + pen + "/delete")));
        owner.browser().htmxPost(path(past) + "/gifts/" + pen + "/delete", Map.of()).then().statusCode(403);

        // It isn't the owner's gift to copy onto a new list.
        UUID upcoming = ownerWishlist("Off-list Next Year", FUTURE_END);
        owner.browser().get(path(upcoming) + "/past-gifts").then().statusCode(200)
                .body(not(containsString("Fancy pen")));
        owner.browser().htmxPost(path(upcoming) + "/gifts/copy", Map.of("gift", pen.toString())).then()
                .statusCode(200).body(containsString("Nothing was copied."));
    }
}
