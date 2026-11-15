package com.giftloop.enrich;

import java.math.BigDecimal;
import java.net.URI;

/**
 * Product details read from a page. Any field may be null when the page doesn't say.
 *
 * @param title    product name, cleaned and at most 200 characters
 * @param price    price in USD (null when missing or in another currency)
 * @param imageUrl absolute product photo URL
 * @param url      canonical page URL (same host as the fetched page), else the fetched URL
 */
public record LinkPreview(String title, BigDecimal price, URI imageUrl, URI url) {

    /**
     * Whether anything useful was found.
     *
     * @return true if a title, price or image is present
     */
    public boolean isEmpty() {
        return title == null && price == null && imageUrl == null;
    }
}
