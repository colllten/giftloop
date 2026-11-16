package com.giftloop.exchange;

import com.giftloop.TestUser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Secret Santa through the real pages: joining, exclusions, the draw and undoing it.
 * Cast: owner (admin), two members who join, and a member who doesn't.
 */
@QuarkusTest
class ExchangeFlowTest {

    private static final Pattern RECIPIENT = Pattern.compile("You're giving a gift to <strong>([^<]+)</strong>");

    private static TestUser owner;
    private static TestUser alex;
    private static TestUser blair;
    private static TestUser late;
    private static UUID groupId;

    /** Lazily creates the shared users and their group. */
    private static synchronized void setUp() {
        if (owner != null) {
            return;
        }
        owner = TestUser.create("Swap Owner");
        alex = TestUser.create("Swap Alex");
        blair = TestUser.create("Swap Blair");
        late = TestUser.create("Swap Late");
        owner.browser().get("/groups/new").then().statusCode(200);
        String location = owner.browser().htmxPost("/groups", Map.of("name", "Swap Family"))
                .then().statusCode(200).extract().header("HX-Redirect");
        groupId = UUID.fromString(location.substring("/groups/".length()));
        String code = owner.browser().request().get("/api/groups/" + groupId).path("joinCode");
        for (TestUser u : List.of(alex, blair, late)) {
            u.browser().get("/join").then().statusCode(200);
            u.browser().htmxPost("/join", Map.of("code", code)).then().header("HX-Redirect", "/groups/" + groupId);
        }
    }

    /** The owner creates an exchange event and returns its path. */
    private static String createExchange(String name) {
        owner.browser().get("/groups/" + groupId + "/events/new").then().statusCode(200);
        Map<String, String> form = new HashMap<>();
        form.put("name", name);
        form.put("endDate", "");
        form.put("type", "exchange");
        form.put("budget", "30");
        return owner.browser().htmxPost("/groups/" + groupId + "/events", form)
                .then().statusCode(200).extract().header("HX-Redirect");
    }

    /** The recipient name on {@code user}'s event page, or null if none is shown. */
    private static String recipientOf(TestUser user, String eventPath) {
        String body = user.browser().get(eventPath).then().statusCode(200).extract().asString();
        Matcher m = RECIPIENT.matcher(body);
        String name = m.find() ? m.group(1) : null;
        if (name != null && m.find()) {
            throw new AssertionError(user.displayName() + " sees more than one recipient");
        }
        return name;
    }

    /** Members join; the draw needs three people, respects exclusions, and each giver sees only their own. */
    @Test
    void drawFlow() {
        setUp();
        String event = createExchange("Swap Night");
        String panel = event + "/exchange";

        alex.browser().get(event).then().statusCode(200).body(containsString("Join the exchange"));
        alex.browser().htmxPost(panel + "/join", Map.of()).then().statusCode(200)
                .body(containsString("Leave the exchange"))
                .body(containsString("Swap Alex (you)"));
        alex.browser().htmxPost(panel + "/draw", Map.of()).then().statusCode(403);

        owner.browser().get(event).then().statusCode(200);
        owner.browser().htmxPost(panel + "/draw", Map.of()).then().statusCode(200)
                .body(containsString("At least 3 people need to join"));
        owner.browser().htmxPost(panel + "/join", Map.of()).then().statusCode(200);
        blair.browser().get(event).then().statusCode(200);
        blair.browser().htmxPost(panel + "/join", Map.of()).then().statusCode(200)
                .body(containsString("3 people joined"));

        // With three people, any exclusion leaves no valid draw.
        owner.browser().htmxPost(panel + "/exclusions", Map.of("userA", alex.id().toString(),
                        "userB", blair.id().toString()))
                .then().statusCode(200).body(containsString("Swap Alex and Swap Blair"));
        alex.browser().get(event).then().statusCode(200).body(not(containsString("Swap Alex and Swap Blair")));
        owner.browser().htmxPost(panel + "/draw", Map.of()).then().statusCode(200)
                .body(containsString("No draw fits these exclusions"));
        owner.browser().htmxPost(panel + "/exclusions/remove", Map.of("userA", blair.id().toString(),
                        "userB", alex.id().toString()))
                .then().statusCode(200).body(not(containsString("Swap Alex and Swap Blair")));

        owner.browser().htmxPost(panel + "/draw", Map.of()).then().statusCode(200)
                .body(containsString("You're giving a gift to"));

        Map<String, String> pairs = new HashMap<>();
        for (TestUser u : List.of(owner, alex, blair)) {
            String recipient = recipientOf(u, event);
            assertNotNull(recipient, u.displayName() + " has a recipient");
            assertNotEquals(u.displayName(), recipient, "nobody draws themselves");
            pairs.put(u.displayName(), recipient);
        }
        Set<String> receivers = new HashSet<>(pairs.values());
        assertEquals(Set.of("Swap Owner", "Swap Alex", "Swap Blair"), receivers, "everyone receives once");
        late.browser().get(event).then().statusCode(200).body(containsString("You didn't join this exchange"));
        assertEquals(null, recipientOf(late, event));

        // After the draw: no joining or leaving, and the type is locked.
        late.browser().htmxPost(panel + "/join", Map.of()).then().statusCode(200)
                .body(containsString("join now. Names may already be drawn."));
        alex.browser().htmxPost(panel + "/leave", Map.of()).then().statusCode(200)
                .body(containsString("leave now. Ask an admin to undo the draw."));
        owner.browser().get(event + "/edit").then().statusCode(200)
                .body(containsString("the type can't change"));
        owner.browser().htmxPost(event, Map.of("name", "Swap Night", "endDate", "", "type", "standard", "budget", ""))
                .then().statusCode(200).body(containsString("Undo the draw first."));

        // Undo: assignments are gone and people can join again.
        alex.browser().htmxPost(panel + "/reset", Map.of()).then().statusCode(403);
        owner.browser().htmxPost(panel + "/reset", Map.of()).then().statusCode(200)
                .body(containsString("Draw names"));
        assertEquals(null, recipientOf(alex, event));
        late.browser().htmxPost(panel + "/join", Map.of()).then().statusCode(200)
                .body(containsString("4 people joined"));
    }

    /** Admins remove participants before the draw; members can't. */
    @Test
    void adminRemovesParticipant() {
        setUp();
        String event = createExchange("Swap Removal");
        String panel = event + "/exchange";
        blair.browser().get(event).then().statusCode(200);
        blair.browser().htmxPost(panel + "/join", Map.of()).then().statusCode(200);
        alex.browser().get(event).then().statusCode(200);
        alex.browser().htmxPost(panel + "/participants/" + blair.id() + "/remove", Map.of()).then().statusCode(403);

        owner.browser().get(event).then().statusCode(200);
        owner.browser().htmxPost(panel + "/participants/" + blair.id() + "/remove", Map.of()).then().statusCode(200)
                .body(containsString("0 people joined"));
    }
}
