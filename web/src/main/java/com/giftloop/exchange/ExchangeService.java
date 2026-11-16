package com.giftloop.exchange;

import com.giftloop.security.DataAccessException;
import com.giftloop.security.RlsContext;
import com.giftloop.security.Sql;
import com.giftloop.security.SqlWork;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.giftloop.security.Sql.prepare;
import static com.giftloop.security.Sql.sqlState;

/**
 * Secret Santa exchanges, as the calling user. RLS and {@code private.draw_exchange} enforce the
 * rules: members join or leave before the draw, admins manage exclusions and draw, and each giver
 * reads only their own assignment.
 *
 * <p>Deviation from layering: throws JAX-RS {@link ForbiddenException} directly (like
 * {@code EventService}), since the only callers are HTTP resources.
 */
@ApplicationScoped
public class ExchangeService {

    private static final Logger LOG = Logger.getLogger(ExchangeService.class);
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /** {@code private.draw_exchange} DETAIL codes → user-facing messages. */
    private static final Map<String, String> RULE_MESSAGES = Map.of(
            "too_few", "At least " + ExchangeView.MIN_PARTICIPANTS + " people need to join before names are drawn.",
            "no_valid_draw", "No draw fits these exclusions. Remove one and try again.",
            "already_drawn", "Names are already drawn.",
            "not_exchange", "This event isn't a gift exchange.");

    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param rls user-scoped DB access
     */
    public ExchangeService(RlsContext rls) {
        this.rls = rls;
    }

    /**
     * The exchange for an event.
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @return the exchange, or empty if the event is missing, not an exchange, or not visible
     */
    public Optional<ExchangeView> find(JsonWebToken jwt, UUID eventId) {
        return run(jwt, c -> {
            boolean drawn;
            boolean canManage;
            try (PreparedStatement s = prepare(c, """
                    select drawn_at is not null as drawn, private.is_group_admin(group_id) as can_manage
                    from "Event" where id = ? and type = 'exchange'""", eventId);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                drawn = r.getBoolean("drawn");
                canManage = r.getBoolean("can_manage");
            }
            List<ExchangeView.Exclusion> exclusions = canManage ? exclusions(c, eventId) : List.of();
            return Optional.of(new ExchangeView(eventId, drawn, canManage, participants(c, eventId),
                    recipient(c, eventId), exclusions, null));
        });
    }

    /**
     * Joins the exchange (before the draw).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @throws ExchangeRuleException if names are already drawn
     */
    public void join(JsonWebToken jwt, UUID eventId) {
        try {
            rls.inUserTransaction(jwt, c -> {
                try (PreparedStatement s = prepare(c, """
                        insert into "ExchangeParticipant" (event_id) values (?)
                        on conflict do nothing""", eventId)) {
                    return s.executeUpdate();
                }
            });
        } catch (DataAccessException e) {
            if (Sql.INSUFFICIENT_PRIVILEGE.equals(sqlState(e)) || Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.debugf("User %s could not join exchange %s: %s", jwt.getSubject(), eventId, e.getCause().getMessage());
                throw new ExchangeRuleException("You can't join now. Names may already be drawn.");
            }
            throw e;
        }
        LOG.infof("User %s joined exchange %s", jwt.getSubject(), eventId);
    }

    /**
     * Leaves the exchange (before the draw).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @throws ExchangeRuleException if names are already drawn
     */
    public void leave(JsonWebToken jwt, UUID eventId) {
        int rows = run(jwt, c -> {
            try (PreparedStatement s = prepare(c,
                    "delete from \"ExchangeParticipant\" where event_id = ? and user_id = auth.uid()", eventId)) {
                return s.executeUpdate();
            }
        });
        if (rows == 0) {
            throw new ExchangeRuleException("Names are drawn, so you can't leave now. Ask an admin to undo the draw.");
        }
        LOG.infof("User %s left exchange %s", jwt.getSubject(), eventId);
    }

    /**
     * Removes a participant (group owner/admin, before the draw).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @param userId  participant to remove
     * @throws ForbiddenException if the caller may not remove them now
     */
    public void removeParticipant(JsonWebToken jwt, UUID eventId, UUID userId) {
        int rows = run(jwt, c -> {
            try (PreparedStatement s = prepare(c,
                    "delete from \"ExchangeParticipant\" where event_id = ? and user_id = ?", eventId, userId)) {
                return s.executeUpdate();
            }
        });
        if (rows == 0) {
            LOG.warnf("User %s was not allowed to remove %s from exchange %s", jwt.getSubject(), userId, eventId);
            throw new ForbiddenException();
        }
        LOG.infof("User %s removed %s from exchange %s", jwt.getSubject(), userId, eventId);
    }

    /**
     * Stops two participants being drawn for each other (group owner/admin, before the draw).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @param userA   one participant
     * @param userB   the other participant
     * @throws ExchangeRuleException if they're the same person or haven't both joined
     * @throws ForbiddenException    if the caller may not manage exclusions now
     */
    public void addExclusion(JsonWebToken jwt, UUID eventId, UUID userA, UUID userB) {
        if (userA.equals(userB)) {
            throw new ExchangeRuleException("Pick two different people.");
        }
        try {
            run(jwt, c -> {
                try (PreparedStatement s = prepare(c, """
                        insert into "ExchangeExclusion" (event_id, user_a, user_b)
                        values (?, least(?::uuid, ?::uuid), greatest(?::uuid, ?::uuid))
                        on conflict do nothing""", eventId, userA, userB, userA, userB)) {
                    return s.executeUpdate();
                }
            });
        } catch (DataAccessException e) {
            if (FOREIGN_KEY_VIOLATION.equals(sqlState(e))) {
                throw new ExchangeRuleException("Both people need to have joined.");
            }
            throw e;
        }
        LOG.infof("User %s added an exclusion to exchange %s", jwt.getSubject(), eventId);
    }

    /**
     * Allows two participants to be drawn for each other again (group owner/admin, before the draw).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @param userA   one participant
     * @param userB   the other participant
     * @throws ForbiddenException if the caller may not manage exclusions now
     */
    public void removeExclusion(JsonWebToken jwt, UUID eventId, UUID userA, UUID userB) {
        int rows = run(jwt, c -> {
            try (PreparedStatement s = prepare(c, """
                    delete from "ExchangeExclusion"
                    where event_id = ? and user_a = least(?::uuid, ?::uuid) and user_b = greatest(?::uuid, ?::uuid)""",
                    eventId, userA, userB, userA, userB)) {
                return s.executeUpdate();
            }
        });
        if (rows == 0) {
            LOG.warnf("User %s could not remove an exclusion from exchange %s", jwt.getSubject(), eventId);
            throw new ForbiddenException();
        }
        LOG.infof("User %s removed an exclusion from exchange %s", jwt.getSubject(), eventId);
    }

    /**
     * Draws names (group owner/admin).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @throws ExchangeRuleException if too few joined, no draw fits the exclusions, or already drawn
     * @throws ForbiddenException    if the caller is not an owner/admin
     */
    public void draw(JsonWebToken jwt, UUID eventId) {
        call(jwt, "select private.draw_exchange(?)", eventId);
        LOG.infof("User %s drew names for exchange %s", jwt.getSubject(), eventId);
    }

    /**
     * Undoes a draw so names can be drawn again (group owner/admin).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @throws ForbiddenException if the caller is not an owner/admin
     */
    public void reset(JsonWebToken jwt, UUID eventId) {
        call(jwt, "select private.reset_exchange_draw(?)", eventId);
        LOG.infof("User %s undid the draw for exchange %s", jwt.getSubject(), eventId);
    }

    /** Runs a {@code private.*} exchange function, mapping its DETAIL codes to messages. */
    private void call(JsonWebToken jwt, String sql, UUID eventId) {
        try {
            run(jwt, c -> {
                try (PreparedStatement s = prepare(c, sql, eventId)) {
                    return s.execute();
                }
            });
        } catch (DataAccessException e) {
            String message = RULE_MESSAGES.get(Sql.detail(e));
            if (Sql.CHECK_VIOLATION.equals(sqlState(e)) && message != null) {
                throw new ExchangeRuleException(message);
            }
            throw e;
        }
    }

    private static List<ExchangeView.Participant> participants(Connection c, UUID eventId) throws SQLException {
        try (PreparedStatement s = prepare(c, """
                select p.user_id, coalesce(pr.display_name, pr.username::text) as name, p.user_id = auth.uid() as me
                from "ExchangeParticipant" p
                join "Profile" pr on pr.id = p.user_id
                where p.event_id = ?
                order by lower(coalesce(pr.display_name, pr.username::text)), p.user_id""", eventId);
             ResultSet r = s.executeQuery()) {
            List<ExchangeView.Participant> participants = new ArrayList<>();
            while (r.next()) {
                participants.add(new ExchangeView.Participant(r.getObject("user_id", UUID.class),
                        r.getString("name"), r.getBoolean("me")));
            }
            return participants;
        }
    }

    /** The caller's own assignment; RLS returns no other row. */
    private static ExchangeView.Recipient recipient(Connection c, UUID eventId) throws SQLException {
        try (PreparedStatement s = prepare(c, """
                select a.receiver_id, coalesce(pr.display_name, pr.username::text) as name,
                       (select w.id from "Wishlist" w
                        where w.event_id = a.event_id and w.owner_id = a.receiver_id and w.removed_at is null) as wishlist_id
                from "ExchangeAssignment" a
                join "Profile" pr on pr.id = a.receiver_id
                where a.event_id = ? and a.giver_id = auth.uid()""", eventId);
             ResultSet r = s.executeQuery()) {
            return r.next() ? new ExchangeView.Recipient(r.getObject("receiver_id", UUID.class), r.getString("name"),
                    r.getObject("wishlist_id", UUID.class)) : null;
        }
    }

    private static List<ExchangeView.Exclusion> exclusions(Connection c, UUID eventId) throws SQLException {
        try (PreparedStatement s = prepare(c, """
                select x.user_a, coalesce(a.display_name, a.username::text) as name_a,
                       x.user_b, coalesce(b.display_name, b.username::text) as name_b
                from "ExchangeExclusion" x
                join "Profile" a on a.id = x.user_a
                join "Profile" b on b.id = x.user_b
                where x.event_id = ?
                order by x.created_at, x.user_a, x.user_b""", eventId);
             ResultSet r = s.executeQuery()) {
            List<ExchangeView.Exclusion> exclusions = new ArrayList<>();
            while (r.next()) {
                exclusions.add(ExchangeView.Exclusion.of(r.getObject("user_a", UUID.class), r.getString("name_a"),
                        r.getObject("user_b", UUID.class), r.getString("name_b")));
            }
            return exclusions;
        }
    }

    /** Runs as the user; insufficient-privilege errors become {@link ForbiddenException}. */
    private <T> T run(JsonWebToken jwt, SqlWork<T> work) {
        try {
            return rls.inUserTransaction(jwt, work);
        } catch (DataAccessException e) {
            if (Sql.INSUFFICIENT_PRIVILEGE.equals(sqlState(e))) {
                LOG.warnf("User %s was denied: %s", jwt.getSubject(), e.getCause().getMessage());
                throw new ForbiddenException();
            }
            throw e;
        }
    }
}
