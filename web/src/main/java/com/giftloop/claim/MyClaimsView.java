package com.giftloop.claim;

import java.util.List;

/**
 * Data for the My claims page: upcoming events first, past ones tucked away.
 *
 * @param upcoming events that haven't ended (or have no end date), soonest first
 * @param past     ended events, most recent first
 * @param notice   confirmation, or null
 * @param error    error, or null
 */
public record MyClaimsView(List<MyClaim.EventClaims> upcoming, List<MyClaim.EventClaims> past, String notice,
                           String error) {

    /**
     * Splits the caller's claims (as ordered by {@link ClaimService#myClaims}) into upcoming and past.
     *
     * @param events claims per event
     * @param notice confirmation, or null
     * @param error  error, or null
     * @return view
     */
    public static MyClaimsView of(List<MyClaim.EventClaims> events, String notice, String error) {
        return new MyClaimsView(events.stream().filter(e -> !e.past()).toList(),
                events.stream().filter(MyClaim.EventClaims::past).toList(), notice, error);
    }

    /**
     * Whether the caller has no claims at all.
     *
     * @return true if both lists are empty
     */
    public boolean isEmpty() {
        return upcoming.isEmpty() && past.isEmpty();
    }
}
