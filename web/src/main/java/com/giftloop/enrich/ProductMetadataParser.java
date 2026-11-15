package com.giftloop.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads product details from page HTML. Sources, best first: JSON-LD {@code Product},
 * OpenGraph (and {@code product:price:*}), Twitter cards, schema.org microdata
 * ({@code itemprop}), Amazon's own markup (it publishes none of the above), then
 * {@code <title>}. Each field takes the first source that has it.
 *
 * <p>Prices are kept only in USD (or when no currency is given), since gifts store a bare
 * amount: a wrong currency is worse than a blank.
 */
public final class ProductMetadataParser {

    static final int TITLE_MAX = 200;
    private static final BigDecimal PRICE_MAX = new BigDecimal("99999999.99");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern PRICE_CHARS = Pattern.compile("[^0-9.]");
    /** "Amazon.com: Real name" and "Real name : Amazon.com" style decorations. */
    private static final Pattern AMAZON_PREFIX = Pattern.compile("^Amazon\\.[a-z.]+\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMAZON_SUFFIX = Pattern.compile("\\s*:\\s*[^:]*Amazon\\.[a-z.]+\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATORS = Pattern.compile("\\s+[|\\-–—:]\\s+");
    /** Amazon price boxes, best first; each holds the price in an {@code .a-offscreen} span. */
    private static final List<String> AMAZON_PRICES = List.of(
            "#corePrice_feature_div .a-offscreen",
            "#corePriceDisplay_desktop_feature_div .a-price .a-offscreen",
            "#apex_offerDisplay_desktop .a-offscreen",
            "#tp_price_block_total_price_ww .a-offscreen",
            "#priceblock_dealprice",
            "#priceblock_ourprice");
    /** A plain dollar amount ("$52.99", "$1,049.00"). */
    private static final Pattern DOLLARS = Pattern.compile("^\\$\\s?[0-9][0-9,]*(\\.[0-9]{2})?$");

    private ProductMetadataParser() {
    }

    /**
     * Parses a page.
     *
     * @param html page HTML
     * @param page URL the page was fetched from (resolves relative links)
     * @return what was found (fields may be null)
     */
    public static LinkPreview parse(String html, URI page) {
        Document doc = Jsoup.parse(html, page.toString());
        JsonNode product = jsonLdProduct(doc);
        String siteName = meta(doc, "og:site_name");

        String title = first(
                text(product, "name"),
                meta(doc, "og:title"),
                meta(doc, "twitter:title"),
                itemprop(doc, "name"),
                amazon(page) ? text(doc, "#productTitle") : null,
                doc.title());

        String currency = first(
                product == null ? null : text(offer(product), "priceCurrency"),
                meta(doc, "product:price:currency"),
                meta(doc, "og:price:currency"),
                itemprop(doc, "priceCurrency"));
        String rawPrice = first(
                product == null ? null : offerPrice(offer(product)),
                meta(doc, "product:price:amount"),
                meta(doc, "og:price:amount"),
                itemprop(doc, "price"),
                amazonUsPrice(doc, page));

        String image = first(
                product == null ? null : image(product.get("image")),
                meta(doc, "og:image:secure_url"),
                meta(doc, "og:image"),
                meta(doc, "twitter:image"),
                meta(doc, "twitter:image:src"),
                itempropImage(doc),
                amazon(page) ? amazonImage(doc) : null);

        return new LinkPreview(cleanTitle(title, siteName, page), price(rawPrice, currency),
                absolute(image, page), canonical(doc, page));
    }

    // ------------------------------------------------------------------ JSON-LD

    /** First schema.org Product in any JSON-LD block (top level, arrays or @graph). */
    private static JsonNode jsonLdProduct(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode root;
            try {
                root = JSON.readTree(script.data());
            } catch (Exception e) {
                continue;
            }
            Deque<JsonNode> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 200) {
                JsonNode node = queue.poll();
                if (node == null) {
                    continue;
                }
                if (node.isArray()) {
                    node.forEach(queue::add);
                } else if (node.isObject()) {
                    if (isType(node.get("@type"), "Product") || isType(node.get("@type"), "ProductGroup")) {
                        return node;
                    }
                    if (node.has("@graph")) {
                        queue.add(node.get("@graph"));
                    }
                    if (node.has("mainEntity")) {
                        queue.add(node.get("mainEntity"));
                    }
                }
            }
        }
        return null;
    }

    private static boolean isType(JsonNode type, String wanted) {
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (wanted.equalsIgnoreCase(t.asText())) {
                    return true;
                }
            }
            return false;
        }
        String text = type.asText();
        return wanted.equalsIgnoreCase(text) || text.endsWith("/" + wanted);
    }

    /** First offer (offers may be an object, an array, or an AggregateOffer); ProductGroup uses hasVariant. */
    private static JsonNode offer(JsonNode product) {
        JsonNode offers = product.get("offers");
        if (offers == null && product.has("hasVariant")) {
            JsonNode variant = product.get("hasVariant");
            offers = (variant.isArray() && !variant.isEmpty() ? variant.get(0) : variant).get("offers");
        }
        if (offers != null && offers.isArray()) {
            offers = offers.isEmpty() ? null : offers.get(0);
        }
        return offers;
    }

    private static String offerPrice(JsonNode offer) {
        if (offer == null) {
            return null;
        }
        String price = first(text(offer, "price"), text(offer, "lowPrice"));
        if (price == null && offer.has("priceSpecification")) {
            JsonNode spec = offer.get("priceSpecification");
            price = text(spec.isArray() && !spec.isEmpty() ? spec.get(0) : spec, "price");
        }
        return price;
    }

    /** image may be a string, an array, or an ImageObject with url/contentUrl. */
    private static String image(JsonNode image) {
        if (image == null || image.isNull()) {
            return null;
        }
        if (image.isArray()) {
            return image.isEmpty() ? null : image(image.get(0));
        }
        if (image.isObject()) {
            return first(text(image, "url"), text(image, "contentUrl"));
        }
        return blankToNull(image.asText());
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isValueNode() ? blankToNull(value.asText()) : null;
    }

    // ------------------------------------------------------------------ HTML

    private static String meta(Document doc, String key) {
        Element el = doc.selectFirst("meta[property=" + key + "], meta[name=" + key + "]");
        return el == null ? null : blankToNull(el.attr("content"));
    }

    private static String itemprop(Document doc, String prop) {
        Element el = doc.selectFirst("[itemprop=" + prop + "]");
        if (el == null) {
            return null;
        }
        return blankToNull(el.hasAttr("content") ? el.attr("content") : el.text());
    }

    private static String itempropImage(Document doc) {
        Element el = doc.selectFirst("[itemprop=image]");
        if (el == null) {
            return null;
        }
        return first(blankToNull(el.attr("content")), blankToNull(el.attr("src")), blankToNull(el.attr("href")));
    }

    // ------------------------------------------------------------------ Amazon

    /** Any Amazon store (amazon.com, amazon.co.uk, smile.amazon.de, ...). */
    private static boolean amazon(URI page) {
        String host = page.getHost() == null ? "" : page.getHost().toLowerCase(Locale.ROOT);
        return host.matches("(.+\\.)?amazon\\.[a-z.]+");
    }

    /** Price from amazon.com only, and only a plain dollar amount (other stores' formats differ). */
    private static String amazonUsPrice(Document doc, URI page) {
        String host = page.getHost() == null ? "" : page.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("amazon.com") && !host.endsWith(".amazon.com")) {
            return null;
        }
        for (String selector : AMAZON_PRICES) {
            for (Element el : doc.select(selector)) {
                String price = el.text().strip();
                if (DOLLARS.matcher(price).matches()) {
                    return price;
                }
            }
        }
        return null;
    }

    /** Main product photo: the large version if offered, else the displayed one. */
    private static String amazonImage(Document doc) {
        Element img = doc.selectFirst("#landingImage, #imgBlkFront");
        return img == null ? null : first(blankToNull(img.attr("data-old-hires")), blankToNull(img.attr("src")));
    }

    private static String text(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : blankToNull(el.text());
    }

    /** rel=canonical (or og:url) when it stays on the fetched host; otherwise the fetched URL. */
    private static URI canonical(Document doc, URI page) {
        Element link = doc.selectFirst("link[rel=canonical]");
        URI candidate = absolute(first(link == null ? null : blankToNull(link.attr("href")), meta(doc, "og:url")), page);
        if (candidate != null && page.getHost() != null && page.getHost().equalsIgnoreCase(candidate.getHost())) {
            return candidate;
        }
        return page;
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Collapses whitespace and strips store decorations ("Name | Store", "Amazon.com: Name").
     *
     * @param title    raw title
     * @param siteName og:site_name, if any
     * @param page     page URL (its host also counts as the store name)
     * @return cleaned title at most {@link #TITLE_MAX} characters, or null
     */
    static String cleanTitle(String title, String siteName, URI page) {
        if (title == null) {
            return null;
        }
        String t = SPACES.matcher(title).replaceAll(" ").strip();
        t = AMAZON_PREFIX.matcher(t).replaceFirst("");
        t = AMAZON_SUFFIX.matcher(t).replaceFirst("");
        String host = page.getHost() == null ? "" : page.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        String store = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
        String[] parts = SEPARATORS.split(t);
        if (parts.length > 1) {
            String last = parts[parts.length - 1].toLowerCase(Locale.ROOT).replace(" ", "");
            boolean isStore = (siteName != null && last.equals(siteName.toLowerCase(Locale.ROOT).replace(" ", "")))
                    || (!store.isEmpty() && last.contains(store));
            if (isStore) {
                t = t.substring(0, t.length() - parts[parts.length - 1].length()).strip();
                t = t.replaceFirst("\\s*[|\\-–—:]$", "").strip();
            }
        }
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX).strip() : t;
    }

    /**
     * Parses a price, keeping it only for USD or an unknown currency.
     *
     * @param raw      price text ("$1,299.00", "24.99")
     * @param currency ISO code, or null
     * @return price with 2 decimals, or null
     */
    static BigDecimal price(String raw, String currency) {
        if (raw == null || (currency != null && !"USD".equalsIgnoreCase(currency.strip()))) {
            return null;
        }
        String cleaned = PRICE_CHARS.matcher(raw.replace(",", "")).replaceAll("");
        try {
            BigDecimal value = new BigDecimal(cleaned).setScale(2, java.math.RoundingMode.HALF_UP);
            return value.signum() > 0 && value.compareTo(PRICE_MAX) <= 0 ? value : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static URI absolute(String url, URI page) {
        if (url == null) {
            return null;
        }
        try {
            URI resolved = page.resolve(url.strip().replace(" ", "%20"));
            String scheme = resolved.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) ? resolved : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
