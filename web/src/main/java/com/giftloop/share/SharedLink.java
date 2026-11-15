package com.giftloop.share;

import com.giftloop.wishlist.GiftForm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the link in what a share sheet or bookmarklet sent. Android puts it in {@code text}
 * (often after a product name), others in {@code url}; we take the first web address found.
 */
final class SharedLink {

    private static final Pattern WEB_ADDRESS = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    /** Punctuation that usually ends a sentence rather than the URL. */
    private static final Pattern TRAILING = Pattern.compile("[.,;:!?)\\]]+$");

    private SharedLink() {
    }

    /**
     * First web address in the shared fields, checked in order.
     *
     * @param candidates {@code url}, {@code text}, {@code title} (any may be null)
     * @return absolute http(s) link, or null if none
     */
    static String extract(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Matcher m = WEB_ADDRESS.matcher(candidate);
            if (m.find()) {
                String link = TRAILING.matcher(m.group()).replaceFirst("");
                if (GiftForm.isWebLink(link)) {
                    return link;
                }
            }
        }
        return null;
    }
}
