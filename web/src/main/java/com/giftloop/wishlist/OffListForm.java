package com.giftloop.wishlist;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * An off-list gift as submitted by a giver: a gift they're giving that isn't on the owner's list.
 * Name, price, links and notes follow {@link GiftForm}'s rules (quantity is always 1 and it is
 * shared with everyone, so those fields aren't asked for).
 *
 * @param gift      gift fields, validated as a {@link GiftForm}
 * @param anonymous hide the giver's name from people not on the claim (add only)
 * @param bought    the giver already bought it (add only)
 */
public record OffListForm(GiftForm gift, boolean anonymous, boolean bought) {

    /**
     * Builds a form from raw request values.
     *
     * @param name      raw name
     * @param price     raw price
     * @param links     raw links, one per line
     * @param notes     raw notes
     * @param anonymous raw checkbox value ({@code "on"}/{@code "true"} when checked)
     * @param bought    raw checkbox value ({@code "on"}/{@code "true"} when checked)
     * @return normalized form
     */
    public static OffListForm of(String name, String price, String links, String notes, String anonymous,
                                 String bought) {
        return new OffListForm(GiftForm.of(name, price, "1", links, notes, GiftForm.EVERYONE, List.of()),
                checked(anonymous), checked(bought));
    }

    /**
     * Blank add form; "already bought" starts ticked, since that's the usual case.
     *
     * @return form
     */
    public static OffListForm empty() {
        return new OffListForm(GiftForm.empty(), false, true);
    }

    /**
     * Edit form prefilled from an off-list gift.
     *
     * @param gift the gift
     * @return form
     */
    public static OffListForm from(Gift gift) {
        return new OffListForm(GiftForm.from(gift), false, false);
    }

    /**
     * Validates the gift fields.
     *
     * @return field name → message; empty when valid
     */
    public Map<String, String> validate() {
        return gift.validate();
    }

    /**
     * Trimmed name. Call after {@link #validate()} passes.
     *
     * @return name
     */
    public String name() {
        return gift.name();
    }

    /**
     * Parsed price. Call after {@link #validate()} passes.
     *
     * @return price, or null
     */
    public BigDecimal priceValue() {
        return gift.priceValue();
    }

    /**
     * Links with {@code https://} added where no scheme was typed.
     *
     * @return links, possibly empty
     */
    public List<String> linkList() {
        return gift.linkList();
    }

    /**
     * Trimmed notes.
     *
     * @return notes, or null
     */
    public String notes() {
        return gift.notes();
    }

    private static boolean checked(String value) {
        return "on".equals(value) || "true".equals(value);
    }
}
