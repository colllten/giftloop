package com.giftloop.claim;

import java.util.List;
import java.util.Optional;

/**
 * Every claim on one gift that the caller may see. Only built when the caller is allowed to see
 * claims at all; a gift whose claims are hidden (owner before reveal) has none of these.
 *
 * @param claims claims, oldest first
 */
public record GiftClaims(List<GiftClaim> claims) {

    /** No claims. */
    public static final GiftClaims NONE = new GiftClaims(List.of());

    /**
     * Units claimed across all claims (may exceed what the owner wants).
     *
     * @return total claimed quantity
     */
    public int claimedQuantity() {
        return claims.stream().mapToInt(GiftClaim::quantity).sum();
    }

    /**
     * The caller's claim, if they are on one.
     *
     * @return the first claim the caller participates in
     */
    public Optional<GiftClaim> mine() {
        return claims.stream().filter(GiftClaim::callerIsParticipant).findFirst();
    }

    /**
     * Whether the caller is on any claim (Qute-friendly).
     *
     * @return true if the caller has a claim
     */
    public boolean hasMine() {
        return mine().isPresent();
    }

    /**
     * The caller's claim or null (Qute-friendly).
     *
     * @return the caller's claim, or null
     */
    public GiftClaim myClaim() {
        return mine().orElse(null);
    }

    /**
     * Claims the caller is not on.
     *
     * @return other people's claims
     */
    public List<GiftClaim> others() {
        return claims.stream().filter(c -> !c.callerIsParticipant()).toList();
    }

    /**
     * Whether nothing is claimed yet.
     *
     * @return true if there are no claims
     */
    public boolean isEmpty() {
        return claims.isEmpty();
    }

    /**
     * Whether claims already cover what the owner wants.
     *
     * @param wanted gift quantity
     * @return true if claimed quantity is at least {@code wanted}
     */
    public boolean isFull(int wanted) {
        return claimedQuantity() >= wanted;
    }

    /**
     * Units still unclaimed.
     *
     * @param wanted gift quantity
     * @return remaining units, never below zero
     */
    public int remaining(int wanted) {
        return Math.max(0, wanted - claimedQuantity());
    }

    /**
     * Status text for the gift card chip. Never colour alone (DESIGN.md).
     *
     * @param wanted gift quantity
     * @return "You claimed", "Claimed", "N of M claimed", or null when nothing is claimed
     */
    public String statusLabel(int wanted) {
        if (hasMine()) {
            return "You claimed";
        }
        if (claims.isEmpty()) {
            return null;
        }
        return isFull(wanted) ? "Claimed" : claimedQuantity() + " of " + wanted + " claimed";
    }
}
