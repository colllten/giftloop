package com.giftloop.profile;

import com.giftloop.SupabaseAuthTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * {@code /api/me}: JWT verification and RLS-scoped read of the caller's profile.
 */
@QuarkusTest
class ProfileResourceTest {

    /** Requests without a token are rejected. */
    @Test
    void missingTokenIsUnauthorized() {
        given().when().get("/api/me").then().statusCode(401);
    }

    /** Tokens that fail verification are rejected. */
    @Test
    void invalidTokenIsUnauthorized() {
        given().auth().oauth2("not.a.token")
                .when().get("/api/me").then().statusCode(401);
    }

    /** A real Supabase token returns that user's own profile. */
    @Test
    void validTokenReturnsOwnProfile() {
        given().auth().oauth2(SupabaseAuthTestClient.accessToken("bob"))
                .when().get("/api/me")
                .then().statusCode(200)
                .body("username", is("bob"))
                .body("displayName", is("Bob"))
                .body("id", is("00000000-0000-0000-0000-000000000b0b"));
    }
}
