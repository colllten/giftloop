package com.giftloop.group;

import com.giftloop.security.DataAccessException;
import com.giftloop.security.RlsContext;
import com.giftloop.security.SqlWork;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Groups and membership, as the calling user. RLS and the {@code private.*} group functions
 * decide what is allowed; this class maps their outcomes to exceptions.
 *
 * <p>Deviation from layering: throws JAX-RS {@link ForbiddenException}/{@link BadRequestException}
 * directly (like {@code ProfileResource}) instead of a service-specific hierarchy, since the only
 * callers are HTTP resources.
 */
@ApplicationScoped
public class GroupService {

    private static final Logger LOG = Logger.getLogger(GroupService.class);
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RATE_LIMITED = "PT429";
    private static final String CHECK_VIOLATION = "23514";
    /** Mirrors {@code c_window} in {@code private.join_group_by_code}. */
    private static final Duration JOIN_FAILURE_WINDOW = Duration.ofMinutes(15);

    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param rls user-scoped DB access
     */
    public GroupService(RlsContext rls) {
        this.rls = rls;
    }

    /**
     * The caller's groups, by name.
     *
     * @param jwt caller's verified token
     * @return groups (empty if none)
     */
    public List<GroupSummary> listMine(JsonWebToken jwt) {
        return run(jwt, c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    select g.id, g.name, gu.role::text as role,
                           (select count(*) from "GroupUser" m where m.group_id = g.id) as member_count
                    from "GroupUser" gu
                    join "Group" g on g.id = gu.group_id
                    where gu.user_id = auth.uid()
                    order by lower(g.name), g.id""");
                 ResultSet r = s.executeQuery()) {
                List<GroupSummary> groups = new ArrayList<>();
                while (r.next()) {
                    groups.add(new GroupSummary(r.getObject("id", UUID.class), r.getString("name"),
                            GroupRole.fromDb(r.getString("role")), r.getInt("member_count")));
                }
                return groups;
            }
        });
    }

    /**
     * A group the caller belongs to, with members and (for admins) the join code.
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @return the group, or empty if missing or the caller is not a member
     */
    public Optional<GroupDetail> find(JsonWebToken jwt, UUID groupId) {
        UUID me = UUID.fromString(jwt.getSubject());
        return run(jwt, c -> {
            String name;
            GroupRole myRole;
            try (PreparedStatement s = c.prepareStatement("""
                    select g.name, gu.role::text as role
                    from "Group" g
                    join "GroupUser" gu on gu.group_id = g.id and gu.user_id = auth.uid()
                    where g.id = ?""")) {
                s.setObject(1, groupId);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        return Optional.empty();
                    }
                    name = r.getString("name");
                    myRole = GroupRole.fromDb(r.getString("role"));
                }
            }
            return Optional.of(new GroupDetail(groupId, name, myRole, members(c, groupId, me), joinCode(c, groupId)));
        });
    }

    /**
     * Creates a group; the caller becomes its owner and a join code is generated.
     *
     * @param jwt  caller's verified token
     * @param form validated form
     * @return new group id
     */
    public UUID create(JsonWebToken jwt, GroupForm form) {
        UUID id = run(jwt, c -> {
            try (PreparedStatement s = c.prepareStatement("insert into \"Group\" (name) values (?) returning id")) {
                s.setString(1, form.name());
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    return r.getObject(1, UUID.class);
                }
            }
        });
        LOG.infof("User %s created group %s", jwt.getSubject(), id);
        return id;
    }

    /**
     * Joins the group with this code as a member (no change if already a member). Unknown codes
     * count toward the caller's failed-attempt limit; malformed ones never reach the database.
     *
     * @param jwt  caller's verified token
     * @param code join code in any case, with or without separators
     * @return group id, or empty if the code is malformed or unknown
     * @throws JoinRateLimitedException if the caller entered too many incorrect codes recently
     */
    public Optional<UUID> join(JsonWebToken jwt, String code) {
        String normalized = JoinCode.normalize(code);
        if (!JoinCode.isWellFormed(normalized)) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = run(jwt, c -> {
                try (PreparedStatement s = c.prepareStatement("select private.join_group_by_code(?)")) {
                    s.setString(1, normalized);
                    try (ResultSet r = s.executeQuery()) {
                        r.next();
                        return r.getObject(1, UUID.class);
                    }
                }
            });
        } catch (DataAccessException e) {
            if (RATE_LIMITED.equals(sqlState(e))) {
                Duration retryAfter = retryAfter(e);
                LOG.warnf("User %s is rate limited from joining groups for %s", jwt.getSubject(), retryAfter);
                throw new JoinRateLimitedException(retryAfter);
            }
            throw e;
        }
        if (id == null) {
            LOG.infof("User %s entered an unknown join code", jwt.getSubject());
            return Optional.empty();
        }
        LOG.infof("User %s joined group %s", jwt.getSubject(), id);
        return Optional.of(id);
    }

    /**
     * Renames a group (owner/admin).
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @param form    validated form
     * @throws ForbiddenException if not allowed
     */
    public void rename(JsonWebToken jwt, UUID groupId, GroupForm form) {
        update(jwt, "update \"Group\" set name = ? where id = ?", form.name(), groupId);
        LOG.infof("User %s renamed group %s", jwt.getSubject(), groupId);
    }

    /**
     * Replaces the join code; the old one stops working (owner/admin).
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @throws ForbiddenException if not allowed
     */
    public void regenerateJoinCode(JsonWebToken jwt, UUID groupId) {
        call(jwt, "select private.regenerate_join_code(?)", groupId);
        LOG.infof("User %s regenerated the join code of group %s", jwt.getSubject(), groupId);
    }

    /**
     * Makes a member an admin or a plain member (owner only).
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @param userId  member to change
     * @param role    {@link GroupRole#ADMIN} or {@link GroupRole#MEMBER}
     * @throws BadRequestException if role is owner (use {@link #transferOwnership})
     * @throws ForbiddenException  if not allowed
     */
    public void setRole(JsonWebToken jwt, UUID groupId, UUID userId, GroupRole role) {
        if (role == GroupRole.OWNER) {
            throw new BadRequestException("Use ownership transfer to make someone owner");
        }
        update(jwt, """
                update "GroupUser" set role = ?::public.group_role
                where group_id = ? and user_id = ?""", role.dbValue(), groupId, userId);
        LOG.infof("User %s set role of %s in group %s to %s", jwt.getSubject(), userId, groupId, role);
    }

    /**
     * Removes a member (owner: anyone else; admin: plain members), or leaves when {@code userId}
     * is the caller (not allowed for the owner).
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @param userId  member to remove
     * @throws ForbiddenException if not allowed
     */
    public void removeMember(JsonWebToken jwt, UUID groupId, UUID userId) {
        update(jwt, "delete from \"GroupUser\" where group_id = ? and user_id = ?", groupId, userId);
        LOG.infof("User %s removed %s from group %s", jwt.getSubject(), userId, groupId);
    }

    /**
     * Hands ownership to another member; the caller becomes an admin (owner only).
     *
     * @param jwt      caller's verified token
     * @param groupId  group id
     * @param newOwner member to become owner
     * @throws ForbiddenException  if the caller is not the owner
     * @throws BadRequestException if {@code newOwner} is not another member
     */
    public void transferOwnership(JsonWebToken jwt, UUID groupId, UUID newOwner) {
        try {
            call(jwt, "select private.transfer_group_ownership(?, ?)", groupId, newOwner);
        } catch (DataAccessException e) {
            if (CHECK_VIOLATION.equals(sqlState(e))) {
                throw new BadRequestException("New owner must be another member of the group");
            }
            throw e;
        }
        LOG.infof("User %s transferred ownership of group %s to %s", jwt.getSubject(), groupId, newOwner);
    }

    /**
     * Deletes a group with everything in it (owner only).
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @throws ForbiddenException if not allowed
     */
    public void delete(JsonWebToken jwt, UUID groupId) {
        update(jwt, "delete from \"Group\" where id = ?", groupId);
        LOG.infof("User %s deleted group %s", jwt.getSubject(), groupId);
    }

    private static List<GroupMember> members(Connection c, UUID groupId, UUID me) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                select p.id, p.username, p.display_name, p.avatar_path, gu.role::text as role
                from "GroupUser" gu
                join "Profile" p on p.id = gu.user_id
                where gu.group_id = ?
                order by case gu.role when 'owner' then 0 when 'admin' then 1 else 2 end,
                         lower(coalesce(p.display_name, p.username::text)), p.id""")) {
            s.setObject(1, groupId);
            try (ResultSet r = s.executeQuery()) {
                List<GroupMember> members = new ArrayList<>();
                while (r.next()) {
                    UUID id = r.getObject("id", UUID.class);
                    members.add(new GroupMember(id, r.getString("username"), r.getString("display_name"),
                            r.getString("avatar_path"), GroupRole.fromDb(r.getString("role")), id.equals(me)));
                }
                return members;
            }
        }
    }

    /** Join code, or null when RLS hides it (plain members). */
    private static String joinCode(Connection c, UUID groupId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select code from \"GroupJoinCode\" where group_id = ?")) {
            s.setObject(1, groupId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    /** Runs an update/delete; zero affected rows means RLS filtered it out. */
    private void update(JsonWebToken jwt, String sql, Object... params) {
        int rows = run(jwt, c -> {
            try (PreparedStatement s = prepare(c, sql, params)) {
                return s.executeUpdate();
            }
        });
        if (rows == 0) {
            LOG.warnf("User %s was not allowed to: %s", jwt.getSubject(), sql.strip().lines().findFirst().orElse(sql));
            throw new ForbiddenException();
        }
    }

    /** Calls a {@code private.*} function for its side effect. */
    private void call(JsonWebToken jwt, String sql, Object... params) {
        run(jwt, c -> {
            try (PreparedStatement s = prepare(c, sql, params)) {
                return s.execute();
            }
        });
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            s.setObject(i + 1, params[i]);
        }
        return s;
    }

    /** Runs as the user; insufficient-privilege errors become {@link ForbiddenException}. */
    private <T> T run(JsonWebToken jwt, SqlWork<T> work) {
        try {
            return rls.inUserTransaction(jwt, work);
        } catch (DataAccessException e) {
            if (INSUFFICIENT_PRIVILEGE.equals(sqlState(e))) {
                LOG.warnf("User %s was denied: %s", jwt.getSubject(), e.getCause().getMessage());
                throw new ForbiddenException();
            }
            throw e;
        }
    }

    private static String sqlState(DataAccessException e) {
        return e.getCause() instanceof SQLException sql ? sql.getSQLState() : null;
    }

    /**
     * Seconds until retry, from the {@code PT429} error's DETAIL. Falls back to the full
     * window if the detail is missing (it is always set by {@code join_group_by_code}).
     */
    private static Duration retryAfter(DataAccessException e) {
        if (e.getCause() instanceof PSQLException pg && pg.getServerErrorMessage() != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(pg.getServerErrorMessage().getDetail()));
            } catch (NumberFormatException ignored) {
                LOG.warnf("Unexpected join rate limit detail: %s", pg.getServerErrorMessage().getDetail());
            }
        }
        return JOIN_FAILURE_WINDOW;
    }
}
