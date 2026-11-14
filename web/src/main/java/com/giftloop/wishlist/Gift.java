package com.giftloop.wishlist;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A gift on a wishlist, as the caller may see it.
 *
 * @param id           gift id
 * @param name         gift name
 * @param price        price, or null
 * @param links        shop links (http/https), possibly empty
 * @param quantity     how many the owner wants
 * @param notes        notes, or null
 * @param visibleToAll shared with every group member
 * @param viewerIds    allowlist when not shared with everyone; filled in for the owner only
 */
public record Gift(UUID id, String name, BigDecimal price, List<String> links, int quantity, String notes,
                   boolean visibleToAll, Set<UUID> viewerIds) {

    /**
     * Price for display, e.g. {@code $249.99}.
     *
     * @return formatted price, or null if none
     */
    public String priceLabel() {
        return price == null ? null : NumberFormat.getCurrencyInstance(Locale.US).format(price);
    }

    /**
     * Visibility chip text for the owner.
     *
     * @return "Everyone", "Only you", or "Shared with N"
     */
    public String visibilityLabel() {
        if (visibleToAll) {
            return "Everyone";
        }
        int n = viewerIds.size();
        return n == 0 ? "Only you" : "Shared with " + n + (n == 1 ? " person" : " people");
    }
}
