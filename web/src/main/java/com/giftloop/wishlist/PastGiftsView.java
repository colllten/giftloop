package com.giftloop.wishlist;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The owner's gifts on their other wishlists, grouped by event, for "Add from a past list".
 *
 * @param wishlistId wishlist the gifts would be copied to
 * @param events     events with copyable gifts, newest first
 * @param error      message to show (e.g. nothing picked), or null
 */
public record PastGiftsView(UUID wishlistId, List<Event> events, String error) {

    /**
     * One earlier event and the owner's gifts on it.
     *
     * @param name  event name
     * @param gifts gifts, oldest first
     */
    public record Event(String name, List<Item> gifts) {
    }

    /**
     * A gift that can be copied.
     *
     * @param id        gift id
     * @param name      gift name
     * @param price     price, or null
     * @param imagePath photo path, or null
     */
    public record Item(UUID id, String name, BigDecimal price, String imagePath) {

        /**
         * Price for display.
         *
         * @return e.g. {@code $24.99}, or null
         */
        public String priceLabel() {
            return price == null ? null : NumberFormat.getCurrencyInstance(Locale.US).format(price);
        }

        /**
         * Thumbnail URL.
         *
         * @return {@code /gift-images/<path>}, or null
         */
        public String imageUrl() {
            return imagePath == null ? null : "/gift-images/" + imagePath;
        }
    }

    /**
     * Same view with an error message.
     *
     * @param message what went wrong
     * @return view
     */
    public PastGiftsView withError(String message) {
        return new PastGiftsView(wishlistId, events, message);
    }
}
