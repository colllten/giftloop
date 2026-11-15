package com.giftloop.enrich;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-fill end to end against a local "store", plus the per-user rate limit.
 */
class LinkPreviewServiceTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        BufferedImage image = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/product", ex -> {
            byte[] body = """
                    <html><head><meta property="og:title" content="Camp Mug">
                    <meta property="og:image" content="/mug.png">
                    <meta property="product:price:amount" content="18.50"></head></html>""".getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/mug.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.size());
            try (OutputStream out = ex.getResponseBody()) {
                out.write(png.toByteArray());
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    /** Title, price and a resized JPEG photo come back. */
    @Test
    void previewsProduct() throws Exception {
        LinkPreviewService.Result r = new LinkPreviewService(new SafeFetcher(true)).preview("u1", base + "/product");
        assertNull(r.problem());
        assertEquals("Camp Mug", r.preview().title());
        assertEquals(new BigDecimal("18.50"), r.preview().price());
        assertNotNull(r.image());
        BufferedImage photo = ImageIO.read(new ByteArrayInputStream(r.image()));
        assertEquals(640, photo.getWidth());
    }

    /** Refused or unreachable links give a message instead of an exception. */
    @Test
    void reportsFailures() {
        LinkPreviewService service = new LinkPreviewService(new SafeFetcher(false));
        assertNotNull(service.preview("u2", "http://127.0.0.1/").problem());
        assertNotNull(service.preview("u2", "not a url").problem());
    }

    /** Twenty per minute per user; the window slides. */
    @Test
    void rateLimitsPerUser() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        LinkPreviewService service = new LinkPreviewService(new SafeFetcher(false), clock);
        for (int i = 0; i < LinkPreviewService.LIMIT; i++) {
            assertTrue(service.allow("a"));
        }
        assertFalse(service.allow("a"));
        assertTrue(service.allow("b"), "limits are per user");
        now.set(now.get().plus(Duration.ofSeconds(61)));
        assertTrue(service.allow("a"), "window slid");
    }
}
