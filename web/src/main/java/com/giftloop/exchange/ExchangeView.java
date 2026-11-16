package com.giftloop.exchange;

import java.util.List;
import java.util.UUID;

/**
 * A Secret Santa exchange as the caller sees it. Assignments other than the caller's own are never
 * loaded (RLS hides them, even from admins).
 *
 * @param eventId      event id
 * @param drawn        whether names have been drawn
 * @param canManage    whether the caller is a group owner/admin (exclusions, draw, undo)
 * @param participants members who joined, by name
 * @param recipient    who the caller gives to, or null before the draw or if not participating
 * @param exclusions   blocked pairs (admins only; empty for others)
 * @param error        message from a refused action, or null
 */
public record ExchangeView(UUID eventId, boolean drawn, boolean canManage, List<Participant> participants,
                           Recipient recipient, List<Exclusion> exclusions, String error) {

    /** Fewest participants a draw accepts (mirrors {@code private.draw_exchange}). */
    public static final int MIN_PARTICIPANTS = 3;

    /**
     * Copy with an error message.
     *
     * @param message message to show
     * @return view
     */
    public ExchangeView withError(String message) {
        return new ExchangeView(eventId, drawn, canManage, participants, recipient, exclusions, message);
    }

    /**
     * Whether the caller has joined.
     *
     * @return true if the caller is a participant
     */
    public boolean joined() {
        return participants.stream().anyMatch(Participant::me);
    }

    /**
     * Whether an admin may draw now.
     *
     * @return true before the draw with enough participants
     */
    public boolean canDraw() {
        return canManage && !drawn && participants.size() >= MIN_PARTICIPANTS;
    }

    /**
     * Whether more people must join before a draw.
     *
     * @return number still needed, or 0
     */
    public int stillNeeded() {
        return Math.max(0, MIN_PARTICIPANTS - participants.size());
    }

    /**
     * Whether the caller joined and the draw happened but their recipient is gone (they left the
     * group or deleted their account).
     *
     * @return true if an admin should undo the draw and draw again
     */
    public boolean recipientMissing() {
        return drawn && joined() && recipient == null;
    }

    /**
     * A participant.
     *
     * @param userId user id
     * @param name   display name (username fallback)
     * @param me     whether this is the caller
     */
    public record Participant(UUID userId, String name, boolean me) {
    }

    /**
     * The caller's assigned recipient.
     *
     * @param userId     recipient's user id
     * @param name       display name (username fallback)
     * @param wishlistId their live wishlist for this event, or null if they haven't started one
     */
    public record Recipient(UUID userId, String name, UUID wishlistId) {
    }

    /**
     * Two participants who won't be drawn for each other.
     *
     * @param userA one participant
     * @param nameA their name
     * @param userB the other participant
     * @param nameB their name
     */
    public record Exclusion(UUID userA, String nameA, UUID userB, String nameB) {
    }
}
