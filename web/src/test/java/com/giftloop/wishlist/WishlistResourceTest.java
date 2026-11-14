package com.giftloop.wishlist;

import com.giftloop.SupabaseAuthTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code /api/wishlists}: per-gift visibility against the seed data (read-only).
 * Alice's "Spa day" is shared only with Carol.
 */
@QuarkusTest
class WishlistResourceTest {

    private static final String ALICE_LIST = "30000000-0000-0000-0000-000000000001";
    private static final String CAROL = "00000000-0000-0000-0000-00000000ca01";

    /** Requests without a token are rejected. */
    @Test
    void missingTokenIsUnauthorized() {
        given().when().get("/api/wishlists/" + ALICE_LIST).then().statusCode(401);
    }

    /** The owner sees every gift and who restricted ones are shared with. */
    @Test
    void ownerSeesVisibility() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("alice"))
                .when().get("/api/wishlists/" + ALICE_LIST).then().statusCode(200)
                .body("mine", is(true))
                .body("gifts.name", hasItem("Spa day"))
                .body("gifts.find { it.name == 'Spa day' }.visibleToAll", is(false))
                .body("gifts.find { it.name == 'Spa day' }.viewerIds", hasItem(CAROL));
    }

    /** A picked viewer sees the restricted gift, without the viewer list. */
    @Test
    void viewerSeesRestrictedGift() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("carol"))
                .when().get("/api/wishlists/" + ALICE_LIST).then().statusCode(200)
                .body("mine", is(false))
                .body("gifts.name", hasItem("Spa day"))
                .body("gifts.find { it.name == 'Spa day' }.viewerIds", nullValue());
    }

    /** A member who wasn't picked doesn't get the restricted gift. */
    @Test
    void otherMemberDoesNotSeeRestrictedGift() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("bob"))
                .when().get("/api/wishlists/" + ALICE_LIST).then().statusCode(200)
                .body("gifts.name", hasItem("Wool socks"))
                .body("gifts.name", not(hasItem("Spa day")));
    }

    /** Non-members get 404. */
    @Test
    void outsiderGetsNotFound() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("dave"))
                .when().get("/api/wishlists/" + ALICE_LIST).then().statusCode(404);
    }
}
