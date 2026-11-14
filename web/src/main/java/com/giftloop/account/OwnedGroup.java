package com.giftloop.account;

import java.util.List;
import java.util.UUID;

/**
 * A group the caller owns, as shown on the account deletion page.
 *
 * @param id            group id
 * @param name          group name
 * @param autoSuccessor who inherits it if the caller doesn't choose; null when the caller is alone
 * @param members       other members the caller can hand it to, by name
 */
public record OwnedGroup(UUID id, String name, UUID autoSuccessor, List<MemberOption> members) {

    /**
     * A member who can become owner.
     *
     * @param id   user id
     * @param name display name (or username)
     */
    public record MemberOption(UUID id, String name) {
    }

    /**
     * Whether nobody else is in the group, so it is deleted with the account.
     *
     * @return true when the caller is the only member
     */
    public boolean solo() {
        return members.isEmpty();
    }

    /**
     * Name of the automatic successor.
     *
     * @return their name, or null for a solo group
     */
    public String autoSuccessorName() {
        return members.stream().filter(m -> m.id().equals(autoSuccessor)).map(MemberOption::name)
                .findFirst().orElse(null);
    }
}
