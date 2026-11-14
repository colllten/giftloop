package com.giftloop.account;

import java.util.List;
import java.util.Map;

/**
 * Data for the account deletion page and form.
 *
 * @param username the caller's username, which they type to confirm
 * @param groups   groups the caller owns
 * @param form     submitted values (empty on first load)
 * @param error    message to show, or null
 */
public record AccountDeletionView(String username, List<OwnedGroup> groups, AccountDeletionForm form, String error) {

    /**
     * First load: nothing typed or chosen.
     *
     * @param username the caller's username
     * @param groups   groups the caller owns
     * @return view
     */
    public static AccountDeletionView initial(String username, List<OwnedGroup> groups) {
        return new AccountDeletionView(username, groups, new AccountDeletionForm("", Map.of()), null);
    }

    /**
     * Groups that get a new owner (others are in them).
     *
     * @return shared groups
     */
    public List<OwnedGroup> sharedGroups() {
        return groups.stream().filter(g -> !g.solo()).toList();
    }

    /**
     * Groups deleted with the account (nobody else is in them).
     *
     * @return solo groups
     */
    public List<OwnedGroup> soloGroups() {
        return groups.stream().filter(OwnedGroup::solo).toList();
    }
}
