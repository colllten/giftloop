package com.giftloop.event;

import com.giftloop.security.DataAccessException;
import com.giftloop.security.RlsContext;
import com.giftloop.security.Sql;
import com.giftloop.security.SqlWork;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.giftloop.security.Sql.prepare;
import static com.giftloop.security.Sql.sqlState;

/**
 * Events in a group, as the calling user. RLS decides who may read, create, edit and delete them
 * (any member creates; only the group owner/admin edits or deletes).
 *
 * <p>Deviation from layering: throws JAX-RS {@link ForbiddenException} directly (like
 * {@code GroupService}), since the only callers are HTTP resources.
 */
@ApplicationScoped
public class EventService {

    private static final Logger LOG = Logger.getLogger(EventService.class);

    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param rls user-scoped DB access
     */
    public EventService(RlsContext rls) {
        this.rls = rls;
    }

    /**
     * A group's events: dated ones soonest first, then open-ended ones by name.
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @return events (empty if none or the caller is not a member)
     */
    public List<EventSummary> listForGroup(JsonWebToken jwt, UUID groupId) {
        return run(jwt, c -> {
            try (PreparedStatement s = prepare(c, """
                    select id, name, end_date, type
                    from "Event"
                    where group_id = ?
                    order by end_date nulls last, lower(name), id""", groupId);
                 ResultSet r = s.executeQuery()) {
                List<EventSummary> events = new ArrayList<>();
                while (r.next()) {
                    events.add(new EventSummary(r.getObject("id", UUID.class), r.getString("name"),
                            r.getObject("end_date", LocalDate.class), EventType.fromDb(r.getString("type"))));
                }
                return events;
            }
        });
    }

    /**
     * Creates an event in a group (any member).
     *
     * @param jwt     caller's verified token
     * @param groupId group id
     * @param form    validated form
     * @return new event id
     * @throws ForbiddenException if the caller is not a member
     */
    public UUID create(JsonWebToken jwt, UUID groupId, EventForm form) {
        LocalDate endDate = form.endDateValue();
        UUID id = run(jwt, c -> {
            try (PreparedStatement s = prepare(c, """
                    insert into "Event" (group_id, name, end_date, type, budget)
                    values (?, ?, ?, ?::public.event_type, ?) returning id""",
                    groupId, form.name(), endDate == null ? null : Date.valueOf(endDate), form.type().dbValue(),
                    form.budgetValue());
                 ResultSet r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        });
        LOG.infof("User %s created %s event %s in group %s", jwt.getSubject(), form.type().dbValue(), id, groupId);
        return id;
    }

    /**
     * An event with its wishlists.
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @return the event, or empty if missing or the caller is not a member of its group
     */
    public Optional<EventDetail> find(JsonWebToken jwt, UUID eventId) {
        return run(jwt, c -> {
            try (PreparedStatement s = prepare(c, """
                    select e.name, e.end_date, e.group_id, g.name as group_name, e.type, e.budget,
                           e.drawn_at is not null as drawn,
                           private.is_group_admin(e.group_id) as can_manage
                    from "Event" e
                    join "Group" g on g.id = e.group_id
                    where e.id = ?""", eventId);
                 ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                List<EventDetail.EventWishlist> wishlists = wishlists(c, eventId);
                UUID mine = wishlists.stream().filter(EventDetail.EventWishlist::mine)
                        .map(EventDetail.EventWishlist::id).findFirst().orElse(null);
                return Optional.of(new EventDetail(eventId, r.getString("name"),
                        r.getObject("end_date", LocalDate.class), r.getObject("group_id", UUID.class),
                        r.getString("group_name"), EventType.fromDb(r.getString("type")), r.getBigDecimal("budget"),
                        r.getBoolean("drawn"), wishlists, mine, r.getBoolean("can_manage")));
            }
        });
    }

    /**
     * Changes an event's name, end date, type and budget (group owner/admin). Switching an exchange
     * to standard drops its participants and exclusions (database trigger).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @param form    validated form
     * @throws ForbiddenException       if the event is missing or the caller may not edit it
     * @throws EventTypeLockedException if the type changed after names were drawn
     */
    public void update(JsonWebToken jwt, UUID eventId, EventForm form) {
        LocalDate endDate = form.endDateValue();
        int rows = run(jwt, c -> {
            try (PreparedStatement s = prepare(c, """
                    update "Event" set name = ?, end_date = ?, type = ?::public.event_type, budget = ?
                    where id = ?""",
                    form.name(), endDate == null ? null : Date.valueOf(endDate), form.type().dbValue(),
                    form.budgetValue(), eventId)) {
                return s.executeUpdate();
            }
        }, true);
        if (rows == 0) {
            LOG.warnf("User %s was not allowed to edit event %s", jwt.getSubject(), eventId);
            throw new ForbiddenException();
        }
        LOG.infof("User %s edited event %s", jwt.getSubject(), eventId);
    }

    /**
     * Deletes an event with its wishlists, gifts and claims (group owner/admin).
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @return the group the event belonged to
     * @throws ForbiddenException if the event is missing or the caller may not delete it
     */
    public UUID delete(JsonWebToken jwt, UUID eventId) {
        Optional<UUID> groupId = run(jwt, c -> {
            try (PreparedStatement s = prepare(c, "delete from \"Event\" where id = ? returning group_id", eventId);
                 ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getObject(1, UUID.class)) : Optional.<UUID>empty();
            }
        });
        if (groupId.isEmpty()) {
            LOG.warnf("User %s was not allowed to delete event %s", jwt.getSubject(), eventId);
            throw new ForbiddenException();
        }
        LOG.infof("User %s deleted event %s in group %s", jwt.getSubject(), eventId, groupId.get());
        return groupId.get();
    }

    /** Live wishlists of an event, the caller's first; gift counts only include live gifts RLS shows. */
    private static List<EventDetail.EventWishlist> wishlists(Connection c, UUID eventId) throws SQLException {
        try (PreparedStatement s = prepare(c, """
                select w.id, coalesce(p.display_name, p.username::text) as owner_name,
                       (select count(*) from "Gift" gi where gi.wishlist_id = w.id and gi.removed_at is null) as gift_count,
                       w.owner_id = auth.uid() as mine
                from "Wishlist" w
                join "Profile" p on p.id = w.owner_id
                where w.event_id = ? and w.removed_at is null
                order by mine desc, lower(coalesce(p.display_name, p.username::text)), w.id""", eventId);
             ResultSet r = s.executeQuery()) {
            List<EventDetail.EventWishlist> wishlists = new ArrayList<>();
            while (r.next()) {
                wishlists.add(new EventDetail.EventWishlist(r.getObject("id", UUID.class),
                        r.getString("owner_name"), r.getInt("gift_count"), r.getBoolean("mine")));
            }
            return wishlists;
        }
    }

    /** Runs as the user; insufficient-privilege errors become {@link ForbiddenException}. */
    private <T> T run(JsonWebToken jwt, SqlWork<T> work) {
        return run(jwt, work, false);
    }

    /**
     * Runs as the user; insufficient-privilege errors become {@link ForbiddenException} and, when
     * {@code typeChange} is set, a locked-type error becomes {@link EventTypeLockedException}.
     */
    private <T> T run(JsonWebToken jwt, SqlWork<T> work, boolean typeChange) {
        try {
            return rls.inUserTransaction(jwt, work);
        } catch (DataAccessException e) {
            if (typeChange && Sql.CHECK_VIOLATION.equals(sqlState(e)) && "type_locked".equals(Sql.detail(e))) {
                LOG.debugf("User %s tried to change a drawn exchange's type", jwt.getSubject());
                throw new EventTypeLockedException();
            }
            if (Sql.INSUFFICIENT_PRIVILEGE.equals(sqlState(e))) {
                LOG.warnf("User %s was denied: %s", jwt.getSubject(), e.getCause().getMessage());
                throw new ForbiddenException();
            }
            throw e;
        }
    }
}
