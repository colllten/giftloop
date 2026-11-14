package com.giftloop.wishlist;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Wishlist filters by claim state, chosen with {@code ?show=}. Only offered when the caller can see
 * claims; otherwise everything is {@link #ALL}, so a filter can't reveal claims to the owner.
 */
public enum GiftFilter {
    /** Every gift. */
    ALL("all", "All"),
    /** Live gifts with units left to claim (unclaimed or partly claimed). */
    NEEDED("needed", "Still needed"),
    /** Live gifts whose claims cover what the owner wants. */
    FULL("claimed", "Fully claimed"),
    /** Gifts the caller is on a claim for, including ones the owner removed. */
    MINE("mine", "Claimed by me");

    private final String param;
    private final String label;

    GiftFilter(String param, String label) {
        this.param = param;
        this.label = label;
    }

    /**
     * Value used in {@code ?show=}.
     *
     * @return query value
     */
    public String param() {
        return param;
    }

    /**
     * Button text.
     *
     * @return label
     */
    public String label() {
        return label;
    }

    /**
     * Parses a {@code ?show=} value; unknown or missing values mean {@link #ALL}.
     *
     * @param value raw query value
     * @return filter
     */
    public static GiftFilter of(String value) {
        return Arrays.stream(values()).filter(f -> f.param.equals(value)).findFirst().orElse(ALL);
    }

    /**
     * The filter in a page URL, e.g. HTMX's {@code HX-Current-URL}, so a refreshed body keeps it.
     *
     * @param url full or relative URL, or null
     * @return filter from its {@code show} query parameter, or {@link #ALL}
     */
    public static GiftFilter fromUrl(String url) {
        if (url == null) {
            return ALL;
        }
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) {
                return ALL;
            }
            return Arrays.stream(query.split("&"))
                    .filter(p -> p.startsWith("show="))
                    .map(p -> of(p.substring("show=".length())))
                    .findFirst().orElse(ALL);
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }

    /**
     * Filters the caller may choose from.
     *
     * @param ownerView whether the caller owns the wishlist (owners can't claim, so no "mine")
     * @return choices in display order
     */
    public static List<GiftFilter> choices(boolean ownerView) {
        return ownerView ? List.of(ALL, NEEDED, FULL) : List.of(values());
    }

    /**
     * Whether a gift belongs in this filter. Gifts with hidden claims only match {@link #ALL}.
     *
     * @param gift a gift the caller can see
     * @return true if it should be shown
     */
    public boolean matches(Gift gift) {
        if (this == ALL) {
            return true;
        }
        if (!gift.claimsShown()) {
            return false;
        }
        return switch (this) {
            case NEEDED -> !gift.removed() && !gift.claims().isFull(gift.quantity());
            case FULL -> !gift.removed() && gift.claims().isFull(gift.quantity());
            case MINE -> gift.claims().hasMine();
            case ALL -> true;
        };
    }
}
