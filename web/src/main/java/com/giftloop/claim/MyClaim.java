package com.giftloop.claim;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A claim the caller is on, with where the gift came from, for the "My claims" page and API.
 *
 * @param claim           the claim (participants visible to the caller)
 * @param giftId          gift id
 * @param giftName        gift name
 * @param price           gift price, or null
 * @param links           shop links
 * @param wanted          how many the owner wants
 * @param giftRemoved     the owner removed the gift (or its wishlist)
 * @param wishlistId      wishlist id
 * @param wishlistRemoved the owner removed the whole wishlist
 * @param ownerName       wishlist owner's display name
 * @param eventId         event id
 * @param eventName       event name
 * @param endDate         event end date, or null
 * @param past            the event has ended
 * @param groupName       group name
 * @param offList         the gift isn't on the owner's list (a giver added it)
 */
public record MyClaim(GiftClaim claim, UUID giftId, String giftName, BigDecimal price, List<String> links,
                      int wanted, boolean giftRemoved, UUID wishlistId, boolean wishlistRemoved, String ownerName,
                      UUID eventId, String eventName, LocalDate endDate, boolean past, String groupName,
                      boolean offList) {

    /**
     * Price for display, e.g. {@code $249.99}.
     *
     * @return formatted price, or null if none
     */
    public String priceLabel() {
        return price == null ? null : NumberFormat.getCurrencyInstance(Locale.US).format(price);
    }

    /**
     * URL of this claim; claim actions append to it.
     *
     * @return {@code /wishlists/<w>/gifts/<g>/claims/<c>}
     */
    public String claimUrl() {
        return "/wishlists/" + wishlistId + "/gifts/" + giftId + "/claims/" + claim.id();
    }

    /**
     * Whether the gift's wishlist page can still be opened.
     *
     * @return true unless the wishlist was removed
     */
    public boolean wishlistOpen() {
        return !wishlistRemoved;
    }

    /**
     * The caller's claims for one event, as grouped on the page.
     *
     * @param eventId   event id
     * @param eventName event name
     * @param groupName group name
     * @param endDate   end date, or null
     * @param past      whether the event has ended
     * @param claims    the caller's claims for the event
     */
    public record EventClaims(UUID eventId, String eventName, String groupName, LocalDate endDate, boolean past,
                              List<MyClaim> claims) {

        /**
         * End date for display, e.g. {@code December 25, 2026}.
         *
         * @return formatted date, or null when the event has none
         */
        public String endDateLabel() {
            return endDate == null ? null : DATE_FORMAT.format(endDate);
        }
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
}
