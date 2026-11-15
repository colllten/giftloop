package com.giftloop.wishlist;

import com.giftloop.enrich.LinkPreview;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Gift fields as submitted by a form, trimmed and kept as text so invalid input can be shown back.
 * Mirrors the {@code "Gift"} constraints, plus two rules the database does not check: links must be
 * web addresses (they are rendered as hrefs; {@code https://} is added when no scheme is typed and
 * other schemes such as {@code javascript:} are rejected), and a gift shared with selected people
 * needs at least one person.
 *
 * @param name       trimmed name, or null if blank
 * @param price      raw price, or null if blank
 * @param quantity   raw quantity, or null if blank (means 1)
 * @param links      raw links, one per line, or null if blank
 * @param notes      trimmed notes, or null if blank
 * @param visibility {@link #EVERYONE} or {@link #SELECTED}
 * @param viewers    raw user ids of the selected people
 * @param imageData  staged photo as base64 JPEG (from auto-fill, or an upload kept across a failed
 *                   save), or null; re-validated before it is stored
 * @param removeImage owner asked to remove the current photo
 */
public record GiftForm(String name, String price, String quantity, String links, String notes,
                       String visibility, List<String> viewers, String imageData, boolean removeImage) {

    /** Visibility value: every group member. */
    public static final String EVERYONE = "everyone";
    /** Visibility value: only the selected people. */
    public static final String SELECTED = "selected";

    static final int NAME_MAX = 200;
    static final int NOTES_MAX = 1000;
    static final int LINKS_MAX = 10;
    static final int LINK_LENGTH_MAX = 2000;
    /** UI limit; the database only requires a positive quantity. */
    static final int QUANTITY_MAX = 999;
    /** Largest {@code numeric(10, 2)} value. */
    static final BigDecimal PRICE_MAX = new BigDecimal("99999999.99");
    /** Largest staged photo (base64 text); normalized photos are far smaller. */
    static final int IMAGE_DATA_MAX = 2 * 1024 * 1024;
    /** Starts with a URI scheme, e.g. {@code mailto:}; a colon followed by a digit is a port instead. */
    private static final Pattern HAS_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d)");

    /**
     * Builds a form from raw request values.
     *
     * @param name       raw name
     * @param price      raw price; a leading {@code $} and thousands commas are allowed
     * @param quantity   raw quantity
     * @param links      raw links, one per line
     * @param notes      raw notes
     * @param visibility raw visibility; anything but {@link #SELECTED} means everyone
     * @param viewers    raw selected user ids (may be null)
     * @return normalized form
     */
    public static GiftForm of(String name, String price, String quantity, String links, String notes,
                              String visibility, List<String> viewers) {
        return new GiftForm(blankToNull(name), blankToNull(price), blankToNull(quantity), blankToNull(links),
                blankToNull(notes), SELECTED.equals(visibility) ? SELECTED : EVERYONE,
                viewers == null ? List.of() : viewers.stream().filter(v -> v != null && !v.isBlank())
                        .map(String::strip).toList(), null, false);
    }

    /**
     * Same form with photo fields from the request.
     *
     * @param imageData   staged base64 JPEG, or blank
     * @param removeImage whether "Remove photo" was ticked
     * @return form
     */
    public GiftForm withPhoto(String imageData, boolean removeImage) {
        return new GiftForm(name, price, quantity, links, notes, visibility, viewers, blankToNull(imageData),
                removeImage);
    }

    /**
     * Same form with a newly staged photo (replaces any earlier one and cancels removal).
     *
     * @param jpeg normalized JPEG bytes
     * @return form
     */
    public GiftForm withImage(byte[] jpeg) {
        return withPhoto(Base64.getEncoder().encodeToString(jpeg), false);
    }

    /**
     * Fills empty fields from a link preview, never overwriting what the owner typed: name and price
     * when blank, the link appended when not already listed (and there is room), the photo when the
     * gift has none and none is staged.
     *
     * @param preview         details read from the page (may be null)
     * @param link            the link the owner pasted (already a valid web link)
     * @param image           normalized photo, or null
     * @param hasCurrentImage the gift being edited already has a photo it keeps
     * @return filled form
     */
    public GiftForm autofill(LinkPreview preview, String link, byte[] image, boolean hasCurrentImage) {
        String newName = name;
        String newPrice = price;
        if (preview != null && newName == null && preview.title() != null) {
            newName = preview.title();
        }
        if (preview != null && newPrice == null && preview.price() != null) {
            newPrice = preview.price().toPlainString();
        }
        String chosen = preview != null && preview.url() != null ? preview.url().toString() : link;
        List<String> current = new ArrayList<>(linkList());
        boolean listed = current.contains(chosen) || current.contains(link);
        if (!listed && current.size() < LINKS_MAX && isWebLink(chosen)) {
            current.add(chosen);
        }
        GiftForm filled = new GiftForm(newName, newPrice, quantity, current.isEmpty() ? null : String.join("\n", current),
                notes, visibility, viewers, imageData, removeImage);
        boolean hasImage = imageData != null || (hasCurrentImage && !removeImage);
        return image != null && !hasImage ? filled.withImage(image) : filled;
    }

    /**
     * Decoded staged photo.
     *
     * @return bytes, or null if none or not valid base64
     */
    public byte[] imageBytes() {
        if (imageData == null || imageData.length() > IMAGE_DATA_MAX) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(imageData);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Empty form for adding a gift: quantity 1, shared with everyone.
     *
     * @return form
     */
    public static GiftForm empty() {
        return new GiftForm(null, null, "1", null, null, EVERYONE, List.of(), null, false);
    }

    /**
     * Form prefilled from an existing gift (owner view).
     *
     * @param gift the gift
     * @return form
     */
    public static GiftForm from(Gift gift) {
        return new GiftForm(gift.name(), gift.price() == null ? null : gift.price().toPlainString(),
                Integer.toString(gift.quantity()), gift.links().isEmpty() ? null : String.join("\n", gift.links()),
                gift.notes(), gift.visibleToAll() ? EVERYONE : SELECTED,
                gift.viewerIds().stream().map(UUID::toString).toList(), null, false);
    }

    /**
     * Validates the fields.
     *
     * @return field name → message; empty when valid
     */
    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null) {
            errors.put("name", "Please name the gift.");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "Please use " + NAME_MAX + " characters or fewer.");
        }
        if (price != null) {
            BigDecimal p = parsePrice(price);
            if (p == null || p.signum() < 0 || p.scale() > 2 || p.compareTo(PRICE_MAX) > 0) {
                errors.put("price", "Please enter a price like 24.99.");
            }
        }
        Integer q = parseQuantity();
        if (q == null || q < 1 || q > QUANTITY_MAX) {
            errors.put("quantity", "Please enter a number from 1 to " + QUANTITY_MAX + ".");
        }
        List<String> linkList = linkList();
        if (linkList.size() > LINKS_MAX) {
            errors.put("links", "Please add " + LINKS_MAX + " links or fewer.");
        } else if (!linkList.stream().allMatch(GiftForm::isWebLink)) {
            errors.put("links", "Please enter web addresses, like example.com/gift.");
        }
        if (notes != null && notes.length() > NOTES_MAX) {
            errors.put("notes", "Please use " + NOTES_MAX + " characters or fewer.");
        }
        if (imageData != null && imageBytes() == null) {
            errors.put("photo", "Please choose the photo again.");
        }
        if (!visibleToAll()) {
            if (viewers.isEmpty()) {
                errors.put("viewers", "Pick at least one person, or share with everyone.");
            } else if (viewerIds() == null) {
                errors.put("viewers", "Please pick people from the list.");
            }
        }
        return errors;
    }

    /**
     * Parsed price. Call after {@link #validate()} passes.
     *
     * @return price, or null if none was given
     */
    public BigDecimal priceValue() {
        return price == null ? null : parsePrice(price);
    }

    /**
     * Parsed quantity. Call after {@link #validate()} passes.
     *
     * @return quantity, 1 if none was given
     */
    public int quantityValue() {
        Integer q = parseQuantity();
        return q == null ? 1 : q;
    }

    /**
     * Non-blank links, one per line, in order, with {@code https://} added where no scheme was typed.
     *
     * @return links (possibly empty)
     */
    public List<String> linkList() {
        return links == null ? List.of()
                : links.lines().map(String::strip).filter(l -> !l.isEmpty()).map(GiftForm::withScheme).toList();
    }

    /**
     * Adds {@code https://} to a link typed without a scheme ({@code example.com/x}, {@code //example.com}).
     * Links that already have a scheme are returned unchanged for {@link #isWebLink} to judge.
     *
     * @param link stripped link
     * @return link with a scheme
     */
    static String withScheme(String link) {
        if (link.startsWith("//")) {
            return "https:" + link;
        }
        return HAS_SCHEME.matcher(link).lookingAt() ? link : "https://" + link;
    }

    /**
     * Whether the gift is shared with every group member.
     *
     * @return true unless {@link #SELECTED}
     */
    public boolean visibleToAll() {
        return !SELECTED.equals(visibility);
    }

    /**
     * Selected people as ids, ignoring duplicates.
     *
     * @return ids, or null if any value is not a UUID
     */
    public Set<UUID> viewerIds() {
        try {
            return viewers.stream().map(UUID::fromString).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether a person's checkbox should be ticked.
     *
     * @param userId member id
     * @return true if selected
     */
    public boolean isViewer(UUID userId) {
        return viewers.contains(userId.toString());
    }

    private Integer parseQuantity() {
        if (quantity == null) {
            return 1;
        }
        try {
            return Integer.valueOf(quantity);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parsePrice(String raw) {
        String cleaned = raw.replace(",", "");
        if (cleaned.startsWith("$")) {
            cleaned = cleaned.substring(1).strip();
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Absolute http(s) URL with a dotted host (rules out typos like "socks"), short enough to store. */
    static boolean isWebLink(String link) {
        if (link.length() > LINK_LENGTH_MAX) {
            return false;
        }
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null && host.contains(".");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
