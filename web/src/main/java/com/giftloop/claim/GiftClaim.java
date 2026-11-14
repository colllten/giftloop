package com.giftloop.claim;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One claim on a gift, as the caller may see it.
 *
 * @param id              claim id
 * @param quantity        units claimed
 * @param participants    participants visible to the caller (anonymous ones only if the caller is on it)
 * @param callerIsCreator whether the caller created the claim (and may change it)
 */
public record GiftClaim(UUID id, int quantity, List<Participant> participants, boolean callerIsCreator) {

    /**
     * Whether the caller is on this claim.
     *
     * @return true if the caller is a participant
     */
    public boolean callerIsParticipant() {
        return participants.stream().anyMatch(Participant::me);
    }

    /**
     * Whether the caller chose to stay anonymous on this claim.
     *
     * @return true if the caller is an anonymous participant
     */
    public boolean callerIsAnonymous() {
        return participants.stream().anyMatch(p -> p.me() && p.anonymous());
    }

    /**
     * Whether a user is already on this claim.
     *
     * @param userId user id
     * @return true if they are a visible participant
     */
    public boolean hasParticipant(UUID userId) {
        return participants.stream().anyMatch(p -> p.userId().equals(userId));
    }

    /**
     * Participants other than the caller, for the co-buyer list.
     *
     * @return others on the claim
     */
    public List<Participant> others() {
        return participants.stream().filter(p -> !p.me()).toList();
    }

    /**
     * The caller's user id on this claim, for the "leave" action.
     *
     * @return the caller's id, or null if they aren't on it
     */
    public UUID myUserId() {
        return participants.stream().filter(Participant::me).map(Participant::userId).findFirst().orElse(null);
    }

    /**
     * Co-buyers other than the caller, for display.
     *
     * @return comma-separated names, or empty when the caller is alone (or others are hidden)
     */
    public String othersLabel() {
        return others().stream().map(Participant::label).collect(Collectors.joining(", "));
    }

    /**
     * Who claimed it, for display.
     *
     * @return comma-separated names, or "Someone" when every participant is hidden
     */
    public String who() {
        return participants.isEmpty() ? "Someone"
                : participants.stream().map(Participant::label).collect(Collectors.joining(", "));
    }
}
