package com.giftloop.enrich;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF guards and fetch limits. A local server stands in for retailer sites, with the
 * test-only loopback switch on; every other check still applies.
 */
class SafeFetcherTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page", ex -> respond(ex, 200, "text/html; charset=utf-8", "<title>ok</title>".getBytes()));
        server.createContext("/hop", ex -> {
            ex.getResponseHeaders().add("Location", "/page");
            respond(ex, 302, "text/plain", new byte[0]);
        });
        server.createContext("/to-private", ex -> {
            ex.getResponseHeaders().add("Location", "http://10.0.0.1/admin");
            respond(ex, 302, "text/plain", new byte[0]);
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            respond(ex, 302, "text/plain", new byte[0]);
        });
        server.createContext("/big", ex -> {
            byte[] page = new byte[SafeFetcher.Kind.HTML.maxBytes() + 500_000];
            java.util.Arrays.fill(page, (byte) 'a');
            respond(ex, 200, "text/html", page);
        });
        server.createContext("/big.png", ex -> respond(ex, 200, "image/png", new byte[SafeFetcher.Kind.IMAGE.maxBytes() + 10]));
        server.createContext("/json", ex -> respond(ex, 200, "application/json", "{}".getBytes()));
        server.createContext("/missing", ex -> respond(ex, 404, "text/html", "no".getBytes()));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String type, byte[] body)
            throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private final SafeFetcher local = new SafeFetcher(true);
    private final SafeFetcher strict = new SafeFetcher(false);

    /** Private, loopback, link-local, CGNAT, multicast, ULA and mapped addresses are not public. */
    @Test
    void classifiesAddresses() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "fe80::1", "fd00::1",
                "::ffff:10.0.0.1", "64:ff9b::a00:1", "2001:db8::1"}) {
            assertFalse(SafeFetcher.isPublic(InetAddress.getByName(ip)), ip);
        }
        for (String ip : new String[]{"93.184.216.34", "8.8.8.8", "2606:4700:4700::1111", "::ffff:8.8.8.8"}) {
            assertTrue(SafeFetcher.isPublic(InetAddress.getByName(ip)), ip);
        }
    }

    /** Non-web schemes, user info, odd ports and private hosts are refused before connecting. */
    @Test
    void refusesUnsafeUrls() {
        for (String url : new String[]{"file:///etc/passwd", "ftp://example.com/x", "gopher://example.com",
                "http://user:pw@example.com/", "http://example.com:8080/", "http://127.0.0.1/",
                "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://localhost/"}) {
            assertThrows(SafeFetcher.FetchException.class, () -> strict.check(URI.create(url)), url);
        }
    }

    /** Loopback stays blocked unless the test switch is on. */
    @Test
    void loopbackNeedsTestSwitch() throws Exception {
        assertThrows(SafeFetcher.FetchException.class, () -> strict.fetch(URI.create(base + "/page"), SafeFetcher.Kind.HTML));
        SafeFetcher.Fetched page = local.fetch(URI.create(base + "/page"), SafeFetcher.Kind.HTML);
        assertArrayEquals("<title>ok</title>".getBytes(StandardCharsets.UTF_8), page.body());
    }

    /** Redirects are followed and each hop is re-checked. */
    @Test
    void followsAndChecksRedirects() throws Exception {
        assertEquals(URI.create(base + "/page"), local.fetch(URI.create(base + "/hop"), SafeFetcher.Kind.HTML).uri());
        assertThrows(SafeFetcher.FetchException.class, () -> local.fetch(URI.create(base + "/to-private"), SafeFetcher.Kind.HTML));
        assertThrows(SafeFetcher.FetchException.class, () -> local.fetch(URI.create(base + "/loop"), SafeFetcher.Kind.HTML));
    }

    /** Oversized pages are cut at the cap (details sit near the top), not failed. */
    @Test
    void truncatesLargePages() throws Exception {
        SafeFetcher.Fetched page = local.fetch(URI.create(base + "/big"), SafeFetcher.Kind.HTML);
        assertEquals(SafeFetcher.Kind.HTML.maxBytes(), page.body().length);
    }

    /** Oversized images, wrong content types and error statuses fail. */
    @Test
    void enforcesLimits() {
        assertThrows(SafeFetcher.FetchException.class, () -> local.fetch(URI.create(base + "/big.png"), SafeFetcher.Kind.IMAGE));
        assertThrows(SafeFetcher.FetchException.class, () -> local.fetch(URI.create(base + "/json"), SafeFetcher.Kind.HTML));
        assertThrows(SafeFetcher.FetchException.class, () -> local.fetch(URI.create(base + "/page"), SafeFetcher.Kind.IMAGE));
        assertThrows(SafeFetcher.FetchException.class, () -> local.fetch(URI.create(base + "/missing"), SafeFetcher.Kind.HTML));
    }
}
