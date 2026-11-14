package com.giftloop.account;

import com.giftloop.TestUser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Account deletion through the real pages: owners hand off groups (chosen or automatic),
 * solo groups are deleted, and the session ends.
 */
@QuarkusTest
class AccountDeletionFlowTest {

    private static TestUser heir;
    private static TestUser admin;

    /** Lazily creates the shared members (Quarkus must be running first). */
    private static synchronized void members() {
        if (heir == null) {
            heir = TestUser.create("Heir Hana");
            admin = TestUser.create("Admin Ada");
        }
    }

    /** Creates a group owned by {@code owner} with the heir as member and the admin as admin. */
    private static UUID sharedGroup(TestUser owner, String name) {
        UUID id = createGroup(owner, name);
        String code = owner.browser().request().get("/api/groups/" + id).path("joinCode");
        for (TestUser member : new TestUser[]{heir, admin}) {
            member.browser().get("/join").then().statusCode(200);
            member.browser().htmxPost("/join", Map.of("code", code)).then().statusCode(200);
        }
        owner.browser().htmxPost("/groups/" + id + "/members/" + admin.id() + "/role", Map.of("role", "admin"))
                .then().statusCode(200);
        return id;
    }

    private static UUID createGroup(TestUser owner, String name) {
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", name))
                .then().statusCode(200).extract().header("HX-Redirect");
        return UUID.fromString(location.substring("/groups/".length()));
    }

    private static String username(TestUser user) {
        return user.browser().request().get("/api/me").path("username");
    }

    /** Role of {@code userId} in the group, as seen by {@code viewer}. */
    private static String role(TestUser viewer, UUID groupId, UUID userId) {
        return viewer.browser().request().get("/api/groups/" + groupId)
                .path("members.find { it.userId == '" + userId + "' }.role");
    }

    /** A wrong username changes nothing; the chosen member then takes over and solo groups go. */
    @Test
    void chosenSuccessor() {
        members();
        TestUser owner = TestUser.create("Leaving Lou");
        UUID shared = sharedGroup(owner, "Deletion Chosen");
        createGroup(owner, "Deletion Solo"); // deletion of solo groups is checked in pgTAP

        owner.browser().get("/profile").then().body(containsString("/account/delete"));
        owner.browser().get("/account/delete").then().statusCode(200)
                .body(containsString("New owner of Deletion Chosen"))
                .body(containsString("Automatic: Admin Ada"))
                .body(containsString("Groups that will be deleted"))
                .body(containsString("Deletion Solo"));

        String pick = shared + ":" + heir.id();
        owner.browser().htmxPost("/account/delete", Map.of("username", "not_me", "successor", pick))
                .then().statusCode(200)
                .body(containsString("your username. Type"))
                .body(containsString("selected>Heir Hana"));
        assertEquals("owner", role(heir, shared, owner.id()));

        owner.browser().htmxPost("/account/delete", Map.of("username", username(owner), "successor", pick))
                .then().statusCode(200).header("HX-Redirect", "/account/deleted");

        owner.browser().request().get("/api/me").then().statusCode(401);
        // Another device still holding a valid token is signed out, not shown an error.
        given().cookie("gl_at", owner.accessToken()).redirects().follow(false).get("/profile")
                .then().statusCode(303).header("Location", containsString("/signin"));
        owner.browser().get("/account/deleted").then().statusCode(200)
                .body(containsString("Your account was deleted"));
        assertEquals("owner", role(heir, shared, heir.id()));
        assertEquals("admin", role(heir, shared, admin.id()));
        heir.browser().request().get("/api/groups/" + shared).then()
                .body(not(containsString(owner.id().toString())));
    }

    /** Leaving the choice on automatic hands the group to the admin. */
    @Test
    void automaticSuccessor() {
        members();
        TestUser owner = TestUser.create("Leaving Lee");
        UUID shared = sharedGroup(owner, "Deletion Auto");

        owner.browser().get("/account/delete").then().statusCode(200);
        owner.browser().htmxPost("/account/delete",
                        Map.of("username", username(owner).toUpperCase(), "successor", shared + ":auto"))
                .then().statusCode(200).header("HX-Redirect", "/account/deleted");

        assertEquals("owner", role(heir, shared, admin.id()));
        assertEquals("member", role(heir, shared, heir.id()));
    }
}
