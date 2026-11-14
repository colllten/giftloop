package com.giftloop.claim;

import com.giftloop.SupabaseAuthTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code /api/claims} and claim fields on {@code /api/wishlists} against the seed data (read-only).
 * Bob claimed Alice's headphones; Carol co-buys anonymously. Christmas 2026 hasn't ended.
 */
@QuarkusTest
class ClaimResourceTest {

    private static final String ALICE_LIST = "30000000-0000-0000-0000-000000000001";
    private static final String HEADPHONES = "40000000-0000-0000-0000-000000000001";
    private static final String HEADPHONES_FIELD = "gifts.find { it.id == '" + HEADPHONES + "' }";

    /** Requests without a token are rejected. */
    @Test
    void missingTokenIsUnauthorized() {
        given().when().get("/api/claims").then().statusCode(401);
    }

    /** The creator sees their claim and their anonymous co-buyer. */
    @Test
    void creatorSeesClaim() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("bob"))
                .when().get("/api/claims").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].giftId", is(HEADPHONES))
                .body("[0].creator", is(true))
                .body("[0].anonymous", is(false))
                .body("[0].coBuyers", hasItem("Carol"))
                .body("[0].purchased", is(0))
                .body("[0].ownerName", is("Alice"));
    }

    /** An anonymous co-buyer sees the claim as theirs, but not as creator. */
    @Test
    void coBuyerSeesClaim() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("carol"))
                .when().get("/api/claims").then().statusCode(200)
                .body("[0].creator", is(false))
                .body("[0].anonymous", is(true))
                .body("[0].coBuyers", hasItem("Bob"));
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("carol"))
                .when().get("/api/wishlists/" + ALICE_LIST).then().statusCode(200)
                .body(HEADPHONES_FIELD + ".claimedQuantity", is(1))
                .body(HEADPHONES_FIELD + ".purchasedQuantity", is(0))
                .body(HEADPHONES_FIELD + ".claimedByMe", is(true));
    }

    /** The owner gets no claim fields before the event ends. */
    @Test
    void ownerSeesNoClaims() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("alice"))
                .when().get("/api/wishlists/" + ALICE_LIST).then().statusCode(200)
                .body(HEADPHONES_FIELD + ".claimedQuantity", nullValue())
                .body(HEADPHONES_FIELD + ".claimedByMe", nullValue());
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("alice"))
                .when().get("/api/claims").then().statusCode(200).body("$", empty());
    }

    /** Someone outside every group has no claims. */
    @Test
    void outsiderHasNone() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("dave"))
                .when().get("/api/claims").then().statusCode(200).body("$", empty());
    }
}
