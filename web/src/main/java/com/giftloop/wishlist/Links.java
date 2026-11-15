package com.giftloop.wishlist;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tidies shop links: drops tracking parameters on save and gives a short label (the store's
 * host) for display. Pure string work on the raw URL, so the rest of it is kept exactly.
 */
public final class Links {

    /** Query keys that only track clicks (matched case-insensitively). */
    private static final Set<String> TRACKING = Set.of("fbclid", "gclid", "gclsrc", "dclid", "msclkid", "yclid",
            "twclid", "igshid", "mc_eid", "mc_cid", "_ga", "_gl", "ref", "ref_", "srsltid", "spm");
    /** Amazon's {@code /ref=...} path segment (search position, campaign). */
    private static final Pattern AMAZON_REF = Pattern.compile("/ref=[^/?#]*");

    private Links() {
    }

    /**
     * Removes tracking parameters ({@code utm_*}, {@code fbclid}, {@code gclid}, ...) and Amazon
     * {@code /ref=} segments. Anything that isn't an absolute http(s) URL is returned unchanged.
     *
     * @param link absolute link
     * @return tidied link
     */
    public static String tidy(String link) {
        if (link == null || !(link.startsWith("http://") || link.startsWith("https://"))) {
            return link;
        }
        String fragment = "";
        String rest = link;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash);
            rest = rest.substring(0, hash);
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        if (isAmazon(rest)) {
            int pathStart = rest.indexOf('/', rest.indexOf("//") + 2);
            if (pathStart > 0) {
                rest = rest.substring(0, pathStart) + AMAZON_REF.matcher(rest.substring(pathStart)).replaceAll("");
            }
        }
        String kept = Arrays.stream(query.split("&"))
                .filter(p -> !p.isEmpty() && !isTracking(p))
                .collect(Collectors.joining("&"));
        return rest + (kept.isEmpty() ? "" : "?" + kept) + fragment;
    }

    /**
     * Short label for a link: its host without {@code www.}.
     *
     * @param link absolute link
     * @return e.g. {@code target.com}, or the link itself if it has no host
     */
    public static String label(String link) {
        try {
            String host = new URI(link).getHost();
            if (host == null || host.isBlank()) {
                return link;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.startsWith("www.") ? lower.substring(4) : lower;
        } catch (URISyntaxException | NullPointerException e) {
            return link;
        }
    }

    private static boolean isTracking(String param) {
        int eq = param.indexOf('=');
        String key = URLDecoder.decode(eq < 0 ? param : param.substring(0, eq), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return key.startsWith("utm_") || TRACKING.contains(key);
    }

    private static boolean isAmazon(String link) {
        String host = label(link);
        return host.equals("amazon.com") || host.startsWith("amazon.") || host.contains(".amazon.");
    }
}
