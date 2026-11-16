package com.giftloop.event;

import com.giftloop.TestUser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Editing and deleting events through the real pages: only the group owner/admin may.
 * Cast: owner, admin, member (creates events but can't manage them), outsider.
 * Users and the group are shared to limit sign-ups; each test makes its own event.
 */
@QuarkusTest
class EventFlowTest {

    private static TestUser owner;
    private static TestUser admin;
    private static TestUser member;
    private static TestUser outsider;
    private static UUID groupId;

    /** Lazily creates the shared users and a group with owner, admin and member. */
    private static synchronized void setUp() {
        if (owner != null) {
            return;
        }
        owner = TestUser.create("Event Owner");
        admin = TestUser.create("Event Admin");
        member = TestUser.create("Event Member");
        outsider = TestUser.create("Event Outsider");
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", "Event Family"))
                .then().statusCode(200).extract().header("HX-Redirect");
        groupId = UUID.fromString(location.substring("/groups/".length()));
        String code = owner.browser().request().get("/api/groups/" + groupId).path("joinCode");
        for (TestUser u : List.of(admin, member)) {
            u.browser().get("/join").then().statusCode(200);
            u.browser().htmxPost("/join", Map.of("code", code)).then().header("HX-Redirect", "/groups/" + groupId);
        }
        owner.browser().get(groupPath());
        owner.browser().htmxPost(groupPath() + "/members/" + admin.id() + "/role", Map.of("role", "admin"))
                .then().statusCode(200);
    }

    private static String groupPath() {
        return "/groups/" + groupId;
    }

    /** {@code user} creates an event ending Dec 26, 2026 and returns its id. Call {@link #setUp()} first. */
    private static UUID createEvent(TestUser user, String name) {
        user.browser().get(groupPath() + "/events/new").then().statusCode(200);
        String location = user.browser()
                .htmxPost(groupPath() + "/events", Map.of("name", name, "endDate", "2026-12-26"))
                .then().statusCode(200).extract().header("HX-Redirect");
        return UUID.fromString(location.substring("/events/".length()));
    }

    private static String eventPath(UUID eventId) {
        return "/events/" + eventId;
    }

    /** A plain member can't edit or delete an event, even one they created; outsiders can't either. */
    @Test
    void membersCannotManage() {
        setUp();
        UUID eventId = createEvent(member, "Member Event");

        member.browser().get(eventPath(eventId)).then().statusCode(200)
                .body(not(containsString("Edit event")))
                .body(not(containsString("Delete event")));
        member.browser().get(eventPath(eventId) + "/edit").then().statusCode(403);
        member.browser().htmxPost(eventPath(eventId), Map.of("name", "Hijacked", "endDate", ""))
                .then().statusCode(403);
        member.browser().htmxPost(eventPath(eventId) + "/delete", Map.of()).then().statusCode(403);

        outsider.browser().get(eventPath(eventId) + "/edit").then().statusCode(404);
        outsider.browser().get("/").then().statusCode(200);
        outsider.browser().htmxPost(eventPath(eventId), Map.of("name", "Hijacked", "endDate", ""))
                .then().statusCode(403);
        outsider.browser().htmxPost(eventPath(eventId) + "/delete", Map.of()).then().statusCode(403);

        member.browser().get(eventPath(eventId)).then().statusCode(200)
                .body(containsString("Member Event"))
                .body(not(containsString("Hijacked")));
    }

    /** An admin edits a member's event: validation errors come back in the form, then the change saves. */
    @Test
    void adminEdits() {
        setUp();
        UUID eventId = createEvent(member, "Admin Edit Event");

        admin.browser().get(eventPath(eventId)).then().statusCode(200)
                .body(containsString("Edit event"))
                .body(containsString("Delete event"));
        admin.browser().get(eventPath(eventId) + "/edit").then().statusCode(200)
                .body(containsString("value=\"Admin Edit Event\""))
                .body(containsString("value=\"2026-12-26\""))
                .body(containsString("Save changes"));

        admin.browser().htmxPost(eventPath(eventId), Map.of("name", " ", "endDate", "soon"))
                .then().statusCode(200)
                .body(containsString("Please give the event a name."))
                .body(containsString("Please enter a date like 2026-12-25."))
                .body(containsString("hx-post=\"/events/" + eventId + "\""));

        admin.browser().htmxPost(eventPath(eventId), Map.of("name", "Renamed Event", "endDate", "2027-01-02"))
                .then().statusCode(200).header("HX-Redirect", eventPath(eventId));
        member.browser().get(eventPath(eventId)).then().statusCode(200)
                .body(containsString("Renamed Event"))
                .body(containsString("Ends Jan 2, 2027"));

        owner.browser().get(eventPath(eventId) + "/edit").then().statusCode(200);
        owner.browser().htmxPost(eventPath(eventId), Map.of("name", "Open Event", "endDate", ""))
                .then().statusCode(200).header("HX-Redirect", eventPath(eventId));
        member.browser().get(eventPath(eventId)).then().statusCode(200)
                .body(containsString("Open Event"))
                .body(not(containsString("Ends ")));
    }

    /** The form describes each type; an exchange shows its chip and budget, and can switch back to standard. */
    @Test
    void exchangeType() {
        setUp();
        member.browser().get(groupPath() + "/events/new").then().statusCode(200)
                .body(containsString("Secret Santa / Gift Exchange"))
                .body(containsString(EventType.EXCHANGE.description()))
                .body(containsString(EventType.STANDARD.description()));

        member.browser().htmxPost(groupPath() + "/events",
                        Map.of("name", "Swap", "endDate", "", "type", "exchange", "budget", "lots"))
                .then().statusCode(200).body(containsString("Please enter an amount like 25 or 25.00."));
        String location = member.browser().htmxPost(groupPath() + "/events",
                        Map.of("name", "Swap", "endDate", "", "type", "exchange", "budget", "$40"))
                .then().statusCode(200).extract().header("HX-Redirect");
        UUID eventId = UUID.fromString(location.substring("/events/".length()));

        member.browser().get(eventPath(eventId)).then().statusCode(200)
                .body(containsString("Secret Santa"))
                .body(containsString("Budget $40.00"))
                .body(containsString("Join the exchange"));
        member.browser().get(groupPath()).then().statusCode(200).body(containsString("chip-role\">Secret Santa"));

        admin.browser().get(eventPath(eventId) + "/edit").then().statusCode(200)
                .body(containsString("value=\"40.00\""));
        admin.browser().htmxPost(eventPath(eventId),
                        Map.of("name", "Swap", "endDate", "", "type", "standard", "budget", "40"))
                .then().statusCode(200).header("HX-Redirect", eventPath(eventId));
        member.browser().get(eventPath(eventId)).then().statusCode(200)
                .body(not(containsString("Budget")))
                .body(not(containsString("exchange-panel")));
    }

    /** The owner deletes an event; its wishlists and claims go with it. */
    @Test
    void ownerDeletes() {
        setUp();
        UUID eventId = createEvent(owner, "Doomed Event");
        member.browser().get(eventPath(eventId)).then().statusCode(200);
        String wishlist = member.browser().htmxPost(eventPath(eventId) + "/wishlist", Map.of())
                .then().statusCode(200).extract().header("HX-Redirect");
        Map<String, Object> gift = new HashMap<>();
        gift.put("name", "Doomed Gift");
        gift.put("price", "10");
        gift.put("quantity", "1");
        gift.put("links", "");
        gift.put("notes", "");
        gift.put("visibility", "everyone");
        member.browser().get(wishlist).then().statusCode(200);
        member.browser().htmxPost(wishlist + "/gifts", gift).then().statusCode(200);
        String giftId = member.browser().request().get("/api" + wishlist).path("gifts[0].id");
        admin.browser().get(wishlist).then().statusCode(200);
        admin.browser().htmxPost(wishlist + "/gifts/" + giftId + "/claims", Map.of("quantity", "1"))
                .then().statusCode(200);
        admin.browser().request().get("/api/claims").then().body("findAll { it.eventId == '" + eventId + "' }",
                hasSize(1));

        owner.browser().get(eventPath(eventId)).then().statusCode(200);
        owner.browser().htmxPost(eventPath(eventId) + "/delete", Map.of())
                .then().statusCode(200).header("HX-Redirect", groupPath());

        member.browser().get(eventPath(eventId)).then().statusCode(404);
        member.browser().get(wishlist).then().statusCode(404);
        member.browser().get(groupPath()).then().statusCode(200).body(not(containsString("Doomed Event")));
        admin.browser().request().get("/api/claims").then().body("findAll { it.eventId == '" + eventId + "' }",
                hasSize(0));
    }
}
