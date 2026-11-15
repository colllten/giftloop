package com.giftloop.enrich;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Product details from page HTML fixtures ({@code src/test/resources/enrich/}).
 */
class ProductMetadataParserTest {

    private static LinkPreview parse(String fixture, String pageUrl) throws Exception {
        try (InputStream in = ProductMetadataParserTest.class.getResourceAsStream("/enrich/" + fixture)) {
            return ProductMetadataParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), URI.create(pageUrl));
        }
    }

    /** JSON-LD Product inside @graph wins over OpenGraph; relative image and canonical resolve. */
    @Test
    void prefersJsonLdProduct() throws Exception {
        LinkPreview p = parse("jsonld.html", "https://www.peak.example/p/123?utm_source=x");
        assertEquals("Trail Running Shoe", p.title());
        assertEquals(new BigDecimal("129.95"), p.price());
        assertEquals(URI.create("https://www.peak.example/img/shoe.webp"), p.imageUrl());
        assertEquals(URI.create("https://www.peak.example/p/trail-shoe"), p.url());
    }

    /** OpenGraph: site-name suffix stripped, $ and commas parsed, off-host og:url ignored. */
    @Test
    void readsOpenGraph() throws Exception {
        LinkPreview p = parse("og.html", "https://cozy.example/throw");
        assertEquals("Wool Throw Blanket", p.title());
        assertEquals(new BigDecimal("1049.00"), p.price());
        assertEquals(URI.create("https://images.cozy.example/throw.jpg"), p.imageUrl());
        assertEquals(URI.create("https://cozy.example/throw"), p.url());
    }

    /** Only a title: store prefix stripped, unparseable price ignored. */
    @Test
    void fallsBackToTitle() throws Exception {
        LinkPreview p = parse("title-only.html", "https://www.amazon.com/dp/B000");
        assertEquals("Pour-Over Coffee Kettle", p.title());
        assertNull(p.price());
        assertNull(p.imageUrl());
    }

    /** Non-USD prices are dropped rather than stored as dollars. */
    @Test
    void dropsOtherCurrencies() throws Exception {
        LinkPreview p = parse("non-usd.html", "https://shop.example.de/tee");
        assertEquals("Teekanne", p.title());
        assertNull(p.price());
        assertEquals(URI.create("https://img.example.de/t.png"), p.imageUrl());
    }

    /** schema.org microdata is used when there is no JSON-LD or OpenGraph. */
    @Test
    void readsMicrodata() throws Exception {
        LinkPreview p = parse("microdata.html", "https://games.example/set");
        assertEquals("Board Game Night Set", p.title());
        assertEquals(new BigDecimal("45.00"), p.price());
        assertEquals(URI.create("https://games.example/bg.png"), p.imageUrl());
    }

    /** Amazon publishes no OpenGraph/JSON-LD: its own title, price box and main image are used. */
    @Test
    void readsAmazonMarkup() throws Exception {
        LinkPreview p = parse("amazon.html", "https://www.amazon.com/dp/B000TEST?th=1");
        assertEquals("Desk Lamp, Brass Finish", p.title());
        assertEquals(new BigDecimal("1049.00"), p.price());
        assertEquals(URI.create("https://m.media.example/I/lamp._SL1000_.jpg"), p.imageUrl());
    }

    /** Other Amazon stores keep title and image but not the price (format and currency differ). */
    @Test
    void amazonPriceOnlyOnAmazonCom() throws Exception {
        LinkPreview p = parse("amazon.html", "https://www.amazon.de/dp/B000TEST");
        assertEquals("Desk Lamp, Brass Finish", p.title());
        assertNull(p.price());
        assertEquals(URI.create("https://m.media.example/I/lamp._SL1000_.jpg"), p.imageUrl());
        assertNull(parse("amazon.html", "https://shop.example/x").price(), "Amazon selectors only on Amazon");
    }

    /** A page with nothing useful is empty. */
    @Test
    void emptyPage() {
        LinkPreview p = ProductMetadataParser.parse("<html><body>hi</body></html>", URI.create("https://x.example/"));
        assertTrue(p.isEmpty());
    }

    /** Title cleanup keeps real separators and caps the length. */
    @Test
    void cleansTitles() {
        URI page = URI.create("https://www.target.com/p/1");
        assertEquals("Lego Set - Space Shuttle", ProductMetadataParser.cleanTitle("Lego Set - Space Shuttle : Target", null, page));
        assertEquals("Lego Set - Space Shuttle", ProductMetadataParser.cleanTitle("Lego Set - Space Shuttle", null, page));
        assertEquals(ProductMetadataParser.TITLE_MAX, ProductMetadataParser.cleanTitle("x".repeat(500), null, page).length());
        assertNull(ProductMetadataParser.cleanTitle("   ", null, page));
    }

    /** Prices: USD or unknown currency only, positive, within numeric(10,2). */
    @Test
    void parsesPrices() {
        assertEquals(new BigDecimal("24.99"), ProductMetadataParser.price("24.99", null));
        assertEquals(new BigDecimal("24.99"), ProductMetadataParser.price("$24.99", "usd"));
        assertNull(ProductMetadataParser.price("24.99", "GBP"));
        assertNull(ProductMetadataParser.price("0", "USD"));
        assertNull(ProductMetadataParser.price("12.99 - 19.99", "USD"));
        assertNull(ProductMetadataParser.price("999999999", "USD"));
    }
}
