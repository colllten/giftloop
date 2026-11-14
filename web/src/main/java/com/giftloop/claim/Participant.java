package com.giftloop.claim;

import java.util.UUID;

/**
 * Someone on a claim, as the caller may see them. RLS hides anonymous participants from anyone not
 * on the same claim, so non-participants never receive those rows.
 *
 * @param userId    participant's user id
 * @param name      display name (username fallback), or "Former member" if their profile is hidden
 * @param anonymous whether they chose to stay anonymous
 * @param me        whether this is the caller
 */
public record Participant(UUID userId, String name, boolean anonymous, boolean me) {

    /**
     * Name for display to the caller.
     *
     * @return "You", or the participant's name (marked when anonymous)
     */
    public String label() {
        if (me) {
            return anonymous ? "You (anonymous)" : "You";
        }
        return anonymous ? name + " (anonymous)" : name;
    }
}
