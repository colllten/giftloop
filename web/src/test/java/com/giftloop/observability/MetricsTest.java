package com.giftloop.observability;

import com.giftloop.TestUser;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus metrics on the management port: built-in binders plus app counters.
 */
@QuarkusTest
class MetricsTest {

    /** Management root already includes {@code /q}. */
    @TestHTTPResource(value = "/metrics", management = true)
    URL metrics;

    @Inject
    MeterRegistry registry;

    private double count(String name, String... tags) {
        return registry.counter(name, tags).count();
    }

    /** The scrape endpoint exposes HTTP, JVM and connection pool metrics. */
    @Test
    void scrapeEndpoint() {
        given().when().get("/");
        given().when().get(metrics).then().statusCode(200)
                .body(containsString("http_server_requests_seconds"))
                .body(containsString("jvm_memory_used_bytes"))
                .body(containsString("agroal_"));
    }

    /** Metrics are not served on the public HTTP port. */
    @Test
    void notOnPublicPort() {
        given().when().get("/q/metrics").then().statusCode(404);
    }

    /** Signing up and creating a group are counted, and the counters appear in the scrape. */
    @Test
    void appCounters() {
        double codes = count("giftloop.auth.codes_sent", "flow", "signup", "outcome", "success");
        double signins = count("giftloop.auth.signins", "method", "code", "outcome", "success");
        double groups = count("giftloop.actions", "action", "group_created");

        TestUser user = TestUser.create("Metric Mo");
        user.browser().get("/groups/new").then().statusCode(200);
        user.browser().htmxPost("/groups", Map.of("name", "Metrics")).then().statusCode(200);

        assertEquals(codes + 1, count("giftloop.auth.codes_sent", "flow", "signup", "outcome", "success"));
        assertEquals(signins + 1, count("giftloop.auth.signins", "method", "code", "outcome", "success"));
        assertEquals(groups + 1, count("giftloop.actions", "action", "group_created"));
        given().when().get(metrics).then()
                .body(containsString("giftloop_actions_total{action=\"group_created\""));
    }

    /** WARN/ERROR lines from GiftLoop loggers are counted; other loggers and INFO are not. */
    @Test
    void logEventsCounted() {
        String category = "com.giftloop.observability.MetricsTest";
        double errors = count("giftloop.log.events", "level", "error", "logger", category);
        double warns = count("giftloop.log.events", "level", "warn", "logger", category);

        Logger log = Logger.getLogger(category);
        log.error("test error (expected)");
        log.warn("test warning (expected)");
        log.info("not counted");
        Logger.getLogger("org.example.Other").error("not ours (expected)");

        assertEquals(errors + 1, count("giftloop.log.events", "level", "error", "logger", category));
        assertEquals(warns + 1, count("giftloop.log.events", "level", "warn", "logger", category));
        assertTrue(registry.find("giftloop.log.events").tag("logger", "org.example.Other").counters().isEmpty());
    }
}
