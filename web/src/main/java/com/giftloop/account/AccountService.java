package com.giftloop.account;

import com.giftloop.security.RlsContext;
import com.giftloop.security.Sql;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.giftloop.security.Sql.prepare;

/**
 * Account deletion as the calling user. {@code private.delete_my_account} deletes the auth user;
 * a trigger on {@code Profile} hands off groups, claims and wishlists (see docs/DATABASE.md).
 */
@ApplicationScoped
public class AccountService {

    private static final Logger LOG = Logger.getLogger(AccountService.class);

    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param rls user-scoped DB access
     */
    public AccountService(RlsContext rls) {
        this.rls = rls;
    }

    /**
     * Groups the caller owns, with the members who could take over and the automatic pick.
     *
     * @param jwt caller's verified token
     * @return owned groups, by name
     */
    public List<OwnedGroup> ownedGroups(JsonWebToken jwt) {
        return rls.inUserTransaction(jwt, c -> {
            Map<UUID, String> names = new LinkedHashMap<>();
            Map<UUID, UUID> auto = new LinkedHashMap<>();
            try (PreparedStatement s = c.prepareStatement("""
                    select g.id, g.name, private.group_successor(g.id) as successor
                    from "Group" g
                    join "GroupUser" gu on gu.group_id = g.id
                    where gu.user_id = auth.uid() and gu.role = 'owner'
                    order by lower(g.name), g.id""");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    UUID id = r.getObject("id", UUID.class);
                    names.put(id, r.getString("name"));
                    auto.put(id, r.getObject("successor", UUID.class));
                }
            }
            Map<UUID, List<OwnedGroup.MemberOption>> members = otherMembers(c, names.keySet().toArray(UUID[]::new));
            List<OwnedGroup> groups = new ArrayList<>();
            names.forEach((id, name) -> groups.add(
                    new OwnedGroup(id, name, auto.get(id), members.getOrDefault(id, List.of()))));
            return groups;
        });
    }

    /**
     * Hands chosen groups to their new owners, then deletes the caller's account, in one
     * transaction. Groups without a choice pass to the automatic successor (or are deleted
     * when solo).
     *
     * @param jwt  caller's verified token
     * @param form confirmed username and chosen successors
     * @throws AccountDeletionRefusedException if a step is refused; nothing is changed
     */
    public void delete(JsonWebToken jwt, AccountDeletionForm form) {
        rls.inUserTransaction(jwt, c -> {
            for (Map.Entry<UUID, UUID> choice : form.successors().entrySet()) {
                try (PreparedStatement s = prepare(c, "select private.transfer_group_ownership(?, ?)",
                        choice.getKey(), choice.getValue())) {
                    s.execute();
                } catch (SQLException e) {
                    throw refusedOr(e, "Someone you picked is no longer in that group, or you no longer own it. "
                            + "Please check your choices and try again.");
                }
                LOG.infof("User %s handed group %s to %s before deleting their account",
                        jwt.getSubject(), choice.getKey(), choice.getValue());
            }
            try (PreparedStatement s = prepare(c, "select private.delete_my_account(?)", form.username())) {
                s.execute();
            } catch (SQLException e) {
                throw refusedOr(e, "That isn't your username.");
            }
            return null;
        });
        LOG.infof("User %s deleted their account", jwt.getSubject());
    }

    /** Other members of each group, by name. */
    private static Map<UUID, List<OwnedGroup.MemberOption>> otherMembers(Connection c, UUID[] groupIds)
            throws SQLException {
        Map<UUID, List<OwnedGroup.MemberOption>> members = new LinkedHashMap<>();
        if (groupIds.length == 0) {
            return members;
        }
        try (PreparedStatement s = c.prepareStatement("""
                select gu.group_id, p.id, coalesce(p.display_name, p.username::text) as name
                from "GroupUser" gu
                join "Profile" p on p.id = gu.user_id
                where gu.group_id = any(?) and gu.user_id <> auth.uid()
                order by lower(coalesce(p.display_name, p.username::text)), p.id""")) {
            s.setArray(1, c.createArrayOf("uuid", groupIds));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    members.computeIfAbsent(r.getObject("group_id", UUID.class), k -> new ArrayList<>())
                            .add(new OwnedGroup.MemberOption(r.getObject("id", UUID.class), r.getString("name")));
                }
            }
        }
        return members;
    }

    /** Rule violations become a user-facing refusal; anything else is rethrown for RlsContext. */
    private static RuntimeException refusedOr(SQLException e, String message) throws SQLException {
        String state = e.getSQLState();
        if (Sql.CHECK_VIOLATION.equals(state) || Sql.INSUFFICIENT_PRIVILEGE.equals(state)) {
            LOG.debugf("Account deletion refused (%s): %s", state, e.getMessage());
            return new AccountDeletionRefusedException(message);
        }
        throw e;
    }
}
