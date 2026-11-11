package com.giftloop.group;

import com.giftloop.SupabaseAuthTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code /api/groups}: RLS-scoped reads against the seed data (read-only).
 */
@QuarkusTest
class GroupResourceTest {

    private static final String GLOVER_FAMILY = "10000000-0000-0000-0000-000000000001";

    /** Requests without a token are rejected. */
    @Test
    void missingTokenIsUnauthorized() {
        given().when().get("/api/groups").then().statusCode(401);
    }

    /** A member sees the group and its members, but not the join code. */
    @Test
    void memberSeesGroupWithoutCode() {
        String token = SupabaseAuthTestClient.accessToken("bob");
        given().auth().oauth2(token).when().get("/api/groups")
                .then().statusCode(200)
                .body("name", hasItems("Glover Family"))
                .body("find { it.id == '" + GLOVER_FAMILY + "' }.myRole", is("member"))
                .body("find { it.id == '" + GLOVER_FAMILY + "' }.memberCount", is(3));
        given().auth().oauth2(token).when().get("/api/groups/" + GLOVER_FAMILY)
                .then().statusCode(200)
                .body("joinCode", nullValue())
                .body("members[0].username", is("alice"))
                .body("members[0].role", is("owner"))
                .body("members.username", hasItems("alice", "bob", "carol"));
    }

    /** A user in no group gets an empty list and 404 for someone else's group. */
    @Test
    void outsiderSeesNothing() {
        String token = SupabaseAuthTestClient.accessToken("dave");
        given().auth().oauth2(token).when().get("/api/groups").then().statusCode(200).body("$", hasSize(0));
        given().auth().oauth2(token).when().get("/api/groups/" + GLOVER_FAMILY).then().statusCode(404);
    }
}
