package com.giftloop.group;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Group setup workflow through the real pages: create, share code, join, manage members.
 * Each test creates its own group; users are shared to limit sign-ups.
 */
@QuarkusTest
class GroupFlowTest {

    private static TestUser owner;
    private static TestUser second;
    private static TestUser third;
    private static TestUser outsider;

    /** Lazily creates the shared users (Quarkus must be running first). */
    private static synchronized void users() {
        if (owner == null) {
            owner = TestUser.create("Owner Olive");
            second = TestUser.create("Second Sam");
            third = TestUser.create("Third Tess");
            outsider = TestUser.create("Outsider Oz");
        }
    }

    /** Owner creates a group and returns its id. */
    private static UUID createGroup(String name) {
        users();
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", name))
                .then().statusCode(200).extract().header("HX-Redirect");
        return UUID.fromString(location.substring("/groups/".length()));
    }

    private static String joinCode(TestUser user, UUID groupId) {
        return user.browser().request().get("/api/groups/" + groupId).path("joinCode");
    }

    private static Response join(TestUser user, String code) {
        user.browser().get("/join").then().statusCode(200);
        return user.browser().htmxPost("/join", Map.of("code", code));
    }

    private static String path(UUID groupId, String action) {
        return "/groups/" + groupId + action;
    }

    /** Creating validates the name; the creator becomes owner and sees a join code. */
    @Test
    void createGroup() {
        users();
        owner.browser().get("/groups/new").then().statusCode(200);
        owner.browser().htmxPost("/groups", Map.of("name", "   "))
                .then().statusCode(200).body(containsString("Please give your group a name."));
        owner.browser().htmxPost("/groups", Map.of("name", "x".repeat(101)))
                .then().body(containsString("100 characters or fewer"));

        UUID id = createGroup("Create Test");
        String code = joinCode(owner, id);
        owner.browser().get(path(id, "")).then().statusCode(200)
                .body(containsString("Create Test"))
                .body(containsString("You're the owner"))
                .body(containsString("Invite people"))
                .body(containsString(JoinCode.format(code)));
        owner.browser().get("/").then().body(containsString("Create Test"));
    }

    /** Joining validates the code, adds a member, is idempotent, and members can't see the code. */
    @Test
    void joinWithCode() {
        UUID id = createGroup("Join Test");
        String code = joinCode(owner, id);

        join(second, "nope").then().body(containsString("8 letters and numbers"));
        join(second, "ZZZZ-ZZZZ").then().body(containsString("find a group with that code"));
        outsider.browser().get(path(id, "")).then().statusCode(404);

        String friendly = (code.substring(0, 4) + "-" + code.substring(4)).toLowerCase();
        join(second, friendly).then().statusCode(200).header("HX-Redirect", path(id, ""));
        join(second, code).then().header("HX-Redirect", path(id, ""));

        second.browser().get(path(id, "")).then().statusCode(200)
                .body(containsString("You're a member"))
                .body(containsString("Owner Olive"))
                .body(not(containsString("Invite people")))
                .body(not(containsString(JoinCode.format(code))));
        second.browser().request().get("/api/groups/" + id).then()
                .body("joinCode", nullValue())
                .body("myRole", is("member"))
                .body("members.size()", is(2));
        second.browser().get("/").then().body(containsString("Join Test"));
        outsider.browser().get(path(id, "")).then().statusCode(404);
    }

    /** Regenerating replaces the code; the old one stops working. Members can't regenerate. */
    @Test
    void regenerateCode() {
        UUID id = createGroup("Code Test");
        String oldCode = joinCode(owner, id);
        join(second, oldCode);

        second.browser().get(path(id, ""));
        second.browser().htmxPost(path(id, "/code"), Map.of()).then().statusCode(403);

        owner.browser().get(path(id, ""));
        owner.browser().htmxPost(path(id, "/code"), Map.of())
                .then().statusCode(200).body(containsString("The old code no longer works."));
        String newCode = joinCode(owner, id);
        assertNotEquals(oldCode, newCode);

        join(third, oldCode).then().body(containsString("find a group with that code"));
        join(third, newCode).then().header("HX-Redirect", path(id, ""));
    }

    /** Owner promotes/demotes; admins remove plain members only and see the code; members rename nothing. */
    @Test
    void rolesAndRemoval() {
        UUID id = createGroup("Roles Test");
        String code = joinCode(owner, id);
        join(second, code);
        join(third, code);

        owner.browser().get(path(id, ""));
        owner.browser().htmxPost(path(id, "/members/" + second.id() + "/role"), Map.of("role", "admin"))
                .then().statusCode(200).body(containsString("Role updated."));
        owner.browser().htmxPost(path(id, "/members/" + second.id() + "/role"), Map.of("role", "owner"))
                .then().statusCode(400);

        second.browser().get(path(id, "")).then().body(containsString("Invite people"));
        assertEquals(code, joinCode(second, id));
        second.browser().htmxPost(path(id, "/members/" + third.id() + "/role"), Map.of("role", "admin"))
                .then().statusCode(403);
        second.browser().htmxPost(path(id, "/members/" + owner.id() + "/remove"), Map.of())
                .then().statusCode(403);
        second.browser().htmxPost(path(id, "/rename"), Map.of("name", "Renamed Roles"))
                .then().statusCode(200).body(containsString("Group renamed."));
        second.browser().htmxPost(path(id, "/members/" + third.id() + "/remove"), Map.of())
                .then().statusCode(200).body(containsString("Member removed."))
                .body(not(containsString("Third Tess")));
        third.browser().get(path(id, "")).then().statusCode(404);

        owner.browser().htmxPost(path(id, "/members/" + second.id() + "/role"), Map.of("role", "member"))
                .then().statusCode(200);
        second.browser().htmxPost(path(id, "/rename"), Map.of("name", "Hijacked")).then().statusCode(403);
    }

    /** Members can leave; the owner can't until they transfer ownership. */
    @Test
    void leaveAndTransfer() {
        UUID id = createGroup("Leave Test");
        join(second, joinCode(owner, id));

        owner.browser().get(path(id, "")).then().body(containsString("first make another member the owner"));
        owner.browser().htmxPost(path(id, "/leave"), Map.of()).then().statusCode(403);

        owner.browser().htmxPost(path(id, "/transfer"), Map.of("userId", outsider.id().toString()))
                .then().statusCode(400);
        owner.browser().htmxPost(path(id, "/transfer"), Map.of("userId", second.id().toString()))
                .then().statusCode(200).body(containsString("now an admin"));
        second.browser().request().get("/api/groups/" + id).then().body("myRole", is("owner"));

        owner.browser().htmxPost(path(id, "/leave"), Map.of()).then().statusCode(200).header("HX-Redirect", "/");
        owner.browser().get(path(id, "")).then().statusCode(404);
        owner.browser().get("/").then().body(not(containsString("Leave Test")));
    }

    /** Only the owner can delete; deleting removes it for everyone. */
    @Test
    void deleteGroup() {
        UUID id = createGroup("Delete Test");
        join(second, joinCode(owner, id));

        second.browser().get(path(id, ""));
        second.browser().htmxPost(path(id, "/delete"), Map.of()).then().statusCode(403);

        owner.browser().get(path(id, ""));
        owner.browser().htmxPost(path(id, "/delete"), Map.of()).then().statusCode(200).header("HX-Redirect", "/");
        second.browser().get(path(id, "")).then().statusCode(404);
    }

    /** Signed-out visitors to /join are sent to sign in and back, keeping the code. */
    @Test
    void joinRequiresSignIn() {
        new com.giftloop.BrowserSession().get("/join?code=ABCD-EFGH").then().statusCode(303)
                .header("Location", containsString("/signin?next="))
                .header("Location", containsString("code%3DABCD-EFGH"));
    }
}
