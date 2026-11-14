package com.giftloop.claim;

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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Claims through the real pages: claiming, anonymity, co-buyers, the owner reveal, and gifts the
 * owner removes after they were claimed.
 * Cast: owner (group owner and wishlist owner), claimer, co-buyer, bystander (member not on the
 * claim), outsider (no group). Users and the group are shared to limit sign-ups; each test makes
 * its own event.
 */
@QuarkusTest
class ClaimFlowTest {

    private static final String FUTURE_END = "2026-12-26";

    private static TestUser owner;
    private static TestUser claimer;
    private static TestUser cobuyer;
    private static TestUser bystander;
    private static TestUser outsider;
    private static UUID groupId;

    /** Lazily creates the shared users and a group with everyone but the outsider. */
    private static synchronized void setUp() {
        if (owner != null) {
            return;
        }
        owner = TestUser.create("Claim Owner");
        claimer = TestUser.create("Claim Buyer");
        cobuyer = TestUser.create("Claim Cobuyer");
        bystander = TestUser.create("Claim Bystander");
        outsider = TestUser.create("Claim Outsider");
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", "Claim Family"))
                .then().statusCode(200).extract().header("HX-Redirect");
        groupId = UUID.fromString(location.substring("/groups/".length()));
        String code = owner.browser().request().get("/api/groups/" + groupId).path("joinCode");
        for (TestUser u : List.of(claimer, cobuyer, bystander)) {
            u.browser().get("/join").then().statusCode(200);
            u.browser().htmxPost("/join", Map.of("code", code)).then().header("HX-Redirect", "/groups/" + groupId);
        }
    }

    /** An event with the owner's wishlist, ending on {@code endDate}; returns the wishlist id. */
    private static UUID ownerWishlist(String eventName, String endDate) {
        setUp();
        owner.browser().get("/groups/" + groupId + "/events/new").then().statusCode(200);
        String event = owner.browser()
                .htmxPost("/groups/" + groupId + "/events", Map.of("name", eventName, "endDate", endDate))
                .then().statusCode(200).extract().header("HX-Redirect");
        String wishlist = owner.browser().htmxPost(event + "/wishlist", Map.of())
                .then().statusCode(200).extract().header("HX-Redirect");
        return UUID.fromString(wishlist.substring("/wishlists/".length()));
    }

    /** Adds a gift everyone can see; returns its id. */
    private static UUID addGift(UUID wishlistId, String name, int quantity) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", name);
        form.put("price", "20");
        form.put("quantity", String.valueOf(quantity));
        form.put("links", "");
        form.put("notes", "");
        form.put("visibility", "everyone");
        owner.browser().get(path(wishlistId)).then().statusCode(200);
        owner.browser().htmxPost(path(wishlistId) + "/gifts", form).then().statusCode(200);
        return UUID.fromString(owner.browser().request().get("/api/wishlists/" + wishlistId)
                .path("gifts.find { it.name == '" + name + "' }.id"));
    }

    private static String path(UUID wishlistId) {
        return "/wishlists/" + wishlistId;
    }

    private static String giftPath(UUID wishlistId, UUID giftId) {
        return path(wishlistId) + "/gifts/" + giftId;
    }

    /** Claims as {@code user} and returns the new claim's id. */
    private static UUID claim(TestUser user, UUID wishlistId, UUID giftId, int quantity, boolean anonymous) {
        Map<String, Object> form = new HashMap<>();
        form.put("quantity", String.valueOf(quantity));
        if (anonymous) {
            form.put("anonymous", "on");
        }
        user.browser().get(path(wishlistId)).then().statusCode(200);
        user.browser().htmxPost(giftPath(wishlistId, giftId) + "/claims", form).then().statusCode(200)
                .body(containsString("You claimed " + quantity));
        return UUID.fromString(user.browser().request().get("/api/claims")
                .path("find { it.giftId == '" + giftId + "' }.id"));
    }

    private static String claimPath(UUID wishlistId, UUID giftId, UUID claimId) {
        return giftPath(wishlistId, giftId) + "/claims/" + claimId;
    }

    /** Members claim, adjust, over-claim behind a warning, and release; the owner sees nothing. */
    @Test
    void claimAdjustAndRelease() {
        UUID w = ownerWishlist("Claim Basics", FUTURE_END);
        UUID lamp = addGift(w, "Desk lamp", 2);

        bystander.browser().get(path(w)).then().statusCode(200)
                .body(containsString("Claim this gift"))
                .body(not(containsString("fully claimed")));
        claimer.browser().get(path(w)).then().statusCode(200);
        claimer.browser().htmxPost(giftPath(w, lamp) + "/claims", Map.of("quantity", "0")).then().statusCode(200)
                .body(containsString("Please enter a number from 1 to 999."));
        UUID claimId = claim(claimer, w, lamp, 1, false);

        bystander.browser().get(path(w)).then().statusCode(200)
                .body(containsString("1 of 2 claimed"))
                .body(containsString("Claim Buyer claimed 1"));
        bystander.browser().request().get("/api/wishlists/" + w).then()
                .body("gifts.find { it.name == 'Desk lamp' }.claimedQuantity", is(1))
                .body("gifts.find { it.name == 'Desk lamp' }.claimedByMe", is(false));

        // The owner can't see claims before the event ends, and can't claim their own gift.
        owner.browser().get(path(w)).then().statusCode(200)
                .body(containsString("see who claimed what until December 27"))
                .body(not(containsString("Claim Buyer claimed")))
                .body(not(containsString("of 2 claimed")))
                .body(not(containsString("Claim this gift")));
        owner.browser().request().get("/api/wishlists/" + w).then()
                .body("gifts.find { it.name == 'Desk lamp' }.claimedQuantity", nullValue());
        owner.browser().htmxPost(giftPath(w, lamp) + "/claims", Map.of("quantity", "1")).then().statusCode(403);
        outsider.browser().get("/claims").then().statusCode(200);
        outsider.browser().htmxPost(giftPath(w, lamp) + "/claims", Map.of("quantity", "1")).then().statusCode(403);

        claimer.browser().htmxPost(claimPath(w, lamp, claimId), Map.of("quantity", "2", "view", "")).then()
                .statusCode(200).body(containsString("Claim updated.")).body(containsString("You claimed 2"));
        bystander.browser().get(path(w)).then()
                .body(containsString("Claimed"))
                .body(containsString("already fully claimed (2 of 2)"));
        bystander.browser().htmxPost(claimPath(w, lamp, claimId), Map.of("quantity", "5", "view", ""))
                .then().statusCode(403);
        claim(bystander, w, lamp, 1, false);
        cobuyer.browser().request().get("/api/wishlists/" + w).then()
                .body("gifts.find { it.name == 'Desk lamp' }.claimedQuantity", is(3));

        claimer.browser().htmxPost(claimPath(w, lamp, claimId) + "/delete", Map.of("view", "")).then()
                .statusCode(200).body(containsString("Claim released.")).body(containsString("Claim this gift"));
        claimer.browser().request().get("/api/claims").then()
                .body("giftId", not(hasItem(lamp.toString())));
    }

    /** Anonymous claimers are hidden from everyone not on the claim; co-buyers join and leave. */
    @Test
    void anonymityAndCoBuyers() {
        UUID w = ownerWishlist("Claim Together", FUTURE_END);
        UUID bike = addGift(w, "Bike", 1);
        UUID claimId = claim(claimer, w, bike, 1, true);
        String claim = claimPath(w, bike, claimId);

        claimer.browser().get(path(w)).then().body(containsString("your name is hidden"));
        bystander.browser().get(path(w)).then().statusCode(200)
                .body(containsString("Someone claimed 1"))
                .body(not(containsString("Claim Buyer")));

        // The owner can't be added, even by posting directly.
        claimer.browser().htmxPost(claim + "/participants", Map.of("userId", owner.id().toString(), "view", ""))
                .then().statusCode(200).body(containsString("That person can"));
        claimer.browser().htmxPost(claim + "/participants", Map.of("userId", cobuyer.id().toString(), "view", ""))
                .then().statusCode(200).body(containsString("Co-buyer added."))
                .body(containsString("with Claim Cobuyer"));

        // A co-buyer sees the anonymous creator; the bystander still doesn't.
        cobuyer.browser().get(path(w)).then().statusCode(200)
                .body(containsString("You claimed 1</strong> with Claim Buyer (anonymous)"))
                .body(not(containsString("Release claim")))
                .body(containsString("Leave claim"));
        cobuyer.browser().request().get("/api/claims").then()
                .body("find { it.id == '" + claimId + "' }.creator", is(false))
                .body("find { it.id == '" + claimId + "' }.coBuyers", hasItem("Claim Buyer"));
        bystander.browser().get(path(w)).then()
                .body(containsString("Claim Cobuyer claimed 1"))
                .body(not(containsString("Claim Buyer")));

        // Only participants change their own anonymity; only the creator releases.
        bystander.browser().htmxPost(claim + "/anonymous", Map.of("anonymous", "true", "view", ""))
                .then().statusCode(403);
        cobuyer.browser().htmxPost(claim + "/delete", Map.of("view", "")).then().statusCode(403);
        claimer.browser().htmxPost(claim + "/participants/" + claimer.id() + "/delete", Map.of("view", ""))
                .then().statusCode(200).body(containsString("Release the claim instead."));

        cobuyer.browser().htmxPost(claim + "/participants/" + cobuyer.id() + "/delete", Map.of("view", ""))
                .then().statusCode(200).body(containsString("You left the claim."))
                .body(containsString("Claim this gift"));
        claimer.browser().htmxPost(claim + "/anonymous", Map.of("anonymous", "false", "view", ""))
                .then().statusCode(200).body(containsString("Your name is shown."));
        bystander.browser().get(path(w)).then().body(containsString("Claim Buyer claimed 1"));
    }

    /** Owners see claims after the event ends, or when an owner/admin turns on early reveal. */
    @Test
    void ownerReveal() {
        UUID past = ownerWishlist("Claim Last Year", "2026-01-01");
        UUID mug = addGift(past, "Mug", 1);
        claim(claimer, past, mug, 1, false);
        owner.browser().get(path(past)).then().statusCode(200)
                .body(containsString("Claim Buyer claimed 1"))
                .body(not(containsString("see who claimed what")));

        UUID upcoming = ownerWishlist("Claim Reveal", FUTURE_END);
        UUID scarf = addGift(upcoming, "Scarf", 1);
        claim(claimer, upcoming, scarf, 1, false);
        owner.browser().get(path(upcoming)).then().body(not(containsString("Claim Buyer claimed")));

        bystander.browser().get("/groups/" + groupId).then().statusCode(200)
                .body(not(containsString("Show claims to owners now")));
        bystander.browser().htmxPost("/groups/" + groupId + "/claims-visibility", Map.of("visible", "true"))
                .then().statusCode(403);
        owner.browser().get("/groups/" + groupId).then().body(containsString("Show claims to owners now"));
        try {
            owner.browser().htmxPost("/groups/" + groupId + "/claims-visibility", Map.of("visible", "true"))
                    .then().statusCode(200).body(containsString("Hide claims until events end"));
            owner.browser().get(path(upcoming)).then()
                    .body(containsString("Claim Buyer claimed 1"))
                    .body(not(containsString("see who claimed what")));
        } finally {
            owner.browser().htmxPost("/groups/" + groupId + "/claims-visibility", Map.of("visible", "false"))
                    .then().statusCode(200);
        }
        owner.browser().get(path(upcoming)).then().body(not(containsString("Claim Buyer claimed")));
    }

    /** Members filter by claim state; the owner can't use filters before the reveal. */
    @Test
    void filterByClaimState() {
        UUID w = ownerWishlist("Claim Filters", FUTURE_END);
        UUID full = addGift(w, "Filter Full", 1);
        UUID partial = addGift(w, "Filter Partial", 3);
        addGift(w, "Filter Open", 1);
        claim(claimer, w, full, 1, false);
        claim(claimer, w, partial, 1, false);

        bystander.browser().get(path(w)).then().statusCode(200)
                .body(containsString("Filter gifts by claim"))
                .body(containsString("Still needed"));
        bystander.browser().get(path(w) + "?show=needed").then().statusCode(200)
                .body(containsString("Filter Partial"))
                .body(containsString("Filter Open"))
                .body(not(containsString("Filter Full")));
        bystander.browser().get(path(w) + "?show=claimed").then()
                .body(containsString("Filter Full"))
                .body(not(containsString("Filter Partial")))
                .body(not(containsString("Filter Open")));
        bystander.browser().get(path(w) + "?show=mine").then()
                .body(containsString("claimed anything on this list"))
                .body(not(containsString("Filter Full")));
        claimer.browser().get(path(w) + "/body?show=mine").then().statusCode(200)
                .body(not(containsString("<html")))
                .body(containsString("Filter Full"))
                .body(containsString("Filter Partial"))
                .body(not(containsString("Filter Open")));

        // A filter would hint at claims, so the owner gets every gift and no filter bar.
        owner.browser().get(path(w) + "?show=claimed").then().statusCode(200)
                .body(not(containsString("Filter gifts by claim")))
                .body(containsString("Filter Partial"))
                .body(containsString("Filter Open"));
        owner.browser().get(path(w) + "/body?show=needed").then().statusCode(200)
                .body(containsString("Filter Full"));
    }

    /** Removing claimed gifts or wishlists keeps them for claimers only, and the owner can't tell. */
    @Test
    void removedGiftsStayWithClaimers() {
        UUID w = ownerWishlist("Claim Removal", FUTURE_END);
        UUID kept = addGift(w, "Claimed Kite", 1);
        UUID dropped = addGift(w, "Unclaimed Yoyo", 1);
        UUID claimId = claim(claimer, w, kept, 1, false);

        owner.browser().htmxPost(giftPath(w, kept) + "/delete", Map.of()).then().statusCode(200)
                .body(containsString("Gift removed."))
                .body(not(containsString("Claimed Kite")));
        owner.browser().htmxPost(giftPath(w, dropped) + "/delete", Map.of()).then().statusCode(200)
                .body(containsString("Gift removed."));
        owner.browser().htmxPost(giftPath(w, kept) + "/delete", Map.of()).then().statusCode(403);
        owner.browser().htmxPost(giftPath(w, dropped) + "/delete", Map.of()).then().statusCode(403);

        bystander.browser().get(path(w)).then().body(not(containsString("Claimed Kite")));
        claimer.browser().get(path(w)).then().statusCode(200)
                .body(containsString("Claimed Kite"))
                .body(containsString("Removed by Claim Owner"));
        claimer.browser().request().get("/api/claims").then()
                .body("find { it.id == '" + claimId + "' }.removed", is(true));

        // Removing the wishlist keeps the claimed gift on My claims; the owner starts fresh.
        UUID eventId = UUID.fromString(owner.browser().request().get("/api/wishlists/" + w).path("eventId"));
        owner.browser().htmxPost(path(w) + "/delete", Map.of()).then().statusCode(200)
                .header("HX-Redirect", "/events/" + eventId);
        claimer.browser().get(path(w)).then().statusCode(404);
        bystander.browser().get("/events/" + eventId).then().body(not(containsString("Claim Owner")));
        claimer.browser().get("/claims").then().statusCode(200)
                .body(containsString("Claimed Kite"))
                .body(containsString("Removed by Claim Owner"))
                .body(not(containsString("/wishlists/" + w + "#gift")));
        String newList = owner.browser().htmxPost("/events/" + eventId + "/wishlist", Map.of())
                .then().statusCode(200).extract().header("HX-Redirect");
        assertNotEquals(path(w), newList, "a re-created wishlist gets a new id");

        claimer.browser().htmxPost(claimPath(w, kept, claimId) + "/delete", Map.of("view", "claims"))
                .then().statusCode(200)
                .body(containsString("Claim released."))
                .body(not(containsString("Claimed Kite")));
    }
}
