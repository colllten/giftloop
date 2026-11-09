package com.giftloop.security;

import com.giftloop.web.Redirects;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure helpers used by the session layer.
 */
class SessionUnitTest {

    private static String token(long exp) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(("{\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    /** Refresh happens within 5 minutes of expiry or for unreadable tokens. */
    @Test
    void needsRefresh() {
        Instant now = Instant.ofEpochSecond(1_000_000);
        assertFalse(SessionRefreshHandler.needsRefresh(token(now.getEpochSecond() + 3600), now));
        assertTrue(SessionRefreshHandler.needsRefresh(token(now.getEpochSecond() + 60), now));
        assertTrue(SessionRefreshHandler.needsRefresh(token(now.getEpochSecond() - 1), now));
        assertTrue(SessionRefreshHandler.needsRefresh("garbage", now));
    }

    /** Only same-site relative paths are allowed as redirect targets. */
    @Test
    void safeNext() {
        assertEquals("/profile?x=1", Redirects.safeNext("/profile?x=1"));
        assertEquals("/", Redirects.safeNext(null));
        assertEquals("/", Redirects.safeNext("https://evil.example"));
        assertEquals("/", Redirects.safeNext("//evil.example"));
        assertEquals("/", Redirects.safeNext("/\\evil.example"));
        assertEquals("/", Redirects.safeNext("/ok\r\nSet-Cookie: x"));
        assertEquals("/signin?next=%2Fprofile", Redirects.withNext("/signin", "/profile"));
        assertEquals("/signin", Redirects.withNext("/signin", "/"));
    }
}
