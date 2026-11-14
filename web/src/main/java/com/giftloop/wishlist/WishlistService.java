package com.giftloop.wishlist;

import com.giftloop.claim.ClaimQueries;
import com.giftloop.claim.GiftClaims;
import com.giftloop.security.DataAccessException;
import com.giftloop.security.RlsContext;
import com.giftloop.security.Sql;
import com.giftloop.security.SqlWork;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.giftloop.security.Sql.prepare;
import static com.giftloop.security.Sql.sqlState;

/**
 * Wishlists and their gifts, as the calling user. RLS decides who may see each gift (everyone in
 * the group, or the owner's picked viewers) and who may change it (the wishlist owner).
 *
 * <p>Deviation from layering: throws JAX-RS {@link ForbiddenException}/{@link BadRequestException}
 * directly (like {@code GroupService}), since the only callers are HTTP resources.
 */
@ApplicationScoped
public class WishlistService {

    private static final Logger LOG = Logger.getLogger(WishlistService.class);

    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param rls user-scoped DB access
     */
    public WishlistService(RlsContext rls) {
        this.rls = rls;
    }

    /**
     * Creates the caller's wishlist for an event, or returns the existing one.
     *
     * @param jwt     caller's verified token
     * @param eventId event id
     * @return wishlist id
     * @throws ForbiddenException if the caller is not a member of the event's group
     */
    public UUID create(JsonWebToken jwt, UUID eventId) {
        UUID id;
        try {
            id = run(jwt, c -> {
                try (PreparedStatement s = prepare(c, """
                        insert into "Wishlist" (event_id) values (?)
                        on conflict (event_id, owner_id) where removed_at is null do nothing
                        returning id""", eventId);
                     ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        return r.getObject(1, UUID.class);
                    }
                }
                try (PreparedStatement s = prepare(c,
                        "select id from \"Wishlist\" where event_id = ? and owner_id = auth.uid() and removed_at is null",
                        eventId);
                     ResultSet r = s.executeQuery()) {
                    r.next();
                    return r.getObject(1, UUID.class);
                }
            });
        } catch (DataAccessException e) {
            // validate_wishlist_owner rejects non-members before RLS does.
            if (Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.warnf("User %s may not create a wishlist for event %s", jwt.getSubject(), eventId);
                throw new ForbiddenException();
            }
            throw e;
        }
        LOG.infof("User %s opened wishlist %s for event %s", jwt.getSubject(), id, eventId);
        return id;
    }

    /**
     * A wishlist with the gifts the caller can see. The owner also gets each gift's viewers and
     * the members they can share with; others get the members they can add as co-buyers.
     *
     * <p>Claims are loaded for everyone but the owner, and for the owner once revealed (event
     * ended, or the group allows it). Before that the owner's gifts carry no claim data at all,
     * since RLS returns none and "none" must not read as "unclaimed".
     *
     * @param jwt        caller's verified token
     * @param wishlistId wishlist id
     * @return the wishlist, or empty if missing, removed, or the caller is not a member of its group
     */
    public Optional<WishlistDetail> find(JsonWebToken jwt, UUID wishlistId) {
        UUID me = UUID.fromString(jwt.getSubject());
        return run(jwt, c -> {
            try (PreparedStatement s = prepare(c, """
                    select w.owner_id, w.event_id, e.name as event_name, e.group_id, g.name as group_name,
                           coalesce(p.display_name, p.username::text) as owner_name,
                           w.removed_at is not null as removed, e.end_date,
                           coalesce(e.end_date < current_date, false) or g.is_claims_visible_to_owner as revealed
                    from "Wishlist" w
                    join "Event" e on e.id = w.event_id
                    join "Group" g on g.id = e.group_id
                    join "Profile" p on p.id = w.owner_id
                    where w.id = ?""", wishlistId);
                 ResultSet r = s.executeQuery()) {
                if (!r.next() || r.getBoolean("removed")) {
                    return Optional.empty();
                }
                UUID ownerId = r.getObject("owner_id", UUID.class);
                UUID groupId = r.getObject("group_id", UUID.class);
                boolean isOwner = ownerId.equals(me);
                boolean revealed = r.getBoolean("revealed");
                LocalDate endDate = r.getObject("end_date", LocalDate.class);
                Map<UUID, Set<UUID>> viewers = isOwner ? viewers(c, wishlistId) : Map.of();
                List<ViewerOption> members = isOwner ? shareableMembers(c, groupId, ownerId) : List.of();
                List<ViewerOption> coBuyers = isOwner ? List.of() : shareableMembers(c, groupId, ownerId).stream()
                        .filter(m -> !m.userId().equals(me)).toList();
                return Optional.of(new WishlistDetail(wishlistId, r.getObject("event_id", UUID.class),
                        r.getString("event_name"), groupId, r.getString("group_name"), r.getString("owner_name"),
                        isOwner, gifts(c, me, wishlistId, viewers, !isOwner || revealed), members, revealed,
                        endDate == null ? null : endDate.plusDays(1), coBuyers));
            }
        });
    }

    /**
     * Removes the caller's wishlist (owner). Unclaimed gifts are deleted; claimed ones stay visible
     * to their claimers as removed. The owner can't tell which happened.
     *
     * @param jwt        caller's verified token
     * @param wishlistId wishlist id
     * @throws ForbiddenException if not allowed
     */
    public void delete(JsonWebToken jwt, UUID wishlistId) {
        call(jwt, "select private.remove_wishlist(?)", wishlistId);
        LOG.infof("User %s removed wishlist %s", jwt.getSubject(), wishlistId);
    }

    /**
     * Adds a gift and its viewers in one transaction (owner).
     *
     * @param jwt        caller's verified token
     * @param wishlistId wishlist id
     * @param form       validated form
     * @return new gift id
     * @throws ForbiddenException  if the caller does not own the wishlist
     * @throws BadRequestException if a selected viewer is not a member of the group
     */
    public UUID addGift(JsonWebToken jwt, UUID wishlistId, GiftForm form) {
        UUID id = save(jwt, c -> {
            UUID giftId;
            try (PreparedStatement s = c.prepareStatement("""
                    insert into "Gift" (wishlist_id, name, price, links, quantity, notes, is_visible_to_all)
                    values (?, ?, ?, ?, ?, ?, ?)
                    returning id""")) {
                s.setObject(1, wishlistId);
                bindFields(c, s, 2, form);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    giftId = r.getObject(1, UUID.class);
                }
            }
            replaceViewers(c, giftId, form);
            return giftId;
        });
        LOG.infof("User %s added gift %s to wishlist %s", jwt.getSubject(), id, wishlistId);
        return id;
    }

    /**
     * Updates a gift and replaces its viewers in one transaction (owner).
     *
     * @param jwt        caller's verified token
     * @param wishlistId wishlist id
     * @param giftId     gift id
     * @param form       validated form
     * @throws ForbiddenException  if the gift is missing, hidden, or not the caller's
     * @throws BadRequestException if a selected viewer is not a member of the group
     */
    public void updateGift(JsonWebToken jwt, UUID wishlistId, UUID giftId, GiftForm form) {
        save(jwt, c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    update "Gift"
                    set name = ?, price = ?, links = ?, quantity = ?, notes = ?, is_visible_to_all = ?
                    where id = ? and wishlist_id = ?""")) {
                int next = bindFields(c, s, 1, form);
                s.setObject(next, giftId);
                s.setObject(next + 1, wishlistId);
                if (s.executeUpdate() == 0) {
                    LOG.warnf("User %s was not allowed to update gift %s", jwt.getSubject(), giftId);
                    throw new ForbiddenException();
                }
            }
            replaceViewers(c, giftId, form);
            return null;
        });
        LOG.infof("User %s updated gift %s", jwt.getSubject(), giftId);
    }

    /**
     * Removes a gift (owner). Unclaimed gifts are deleted; claimed ones stay visible to their
     * claimers as removed. The owner can't tell which happened.
     *
     * @param jwt        caller's verified token
     * @param wishlistId wishlist id (for the log)
     * @param giftId     gift id
     * @throws ForbiddenException if not allowed
     */
    public void deleteGift(JsonWebToken jwt, UUID wishlistId, UUID giftId) {
        call(jwt, "select private.remove_gift(?)", giftId);
        LOG.infof("User %s removed gift %s from wishlist %s", jwt.getSubject(), giftId, wishlistId);
    }

    /** Binds name, price, links, quantity, notes and visibility from {@code first}; returns the next index. */
    private static int bindFields(Connection c, PreparedStatement s, int first, GiftForm form) throws SQLException {
        List<String> links = form.linkList();
        BigDecimal price = form.priceValue();
        s.setString(first, form.name());
        s.setBigDecimal(first + 1, price);
        s.setArray(first + 2, links.isEmpty() ? null : c.createArrayOf("text", links.toArray()));
        s.setInt(first + 3, form.quantityValue());
        s.setString(first + 4, form.notes());
        s.setBoolean(first + 5, form.visibleToAll());
        return first + 6;
    }

    /** Replaces a gift's allowlist; shared-with-everyone gifts keep none. */
    private static void replaceViewers(Connection c, UUID giftId, GiftForm form) throws SQLException {
        try (PreparedStatement s = prepare(c, "delete from \"GiftViewer\" where gift_id = ?", giftId)) {
            s.executeUpdate();
        }
        if (form.visibleToAll()) {
            return;
        }
        try (PreparedStatement s = c.prepareStatement("insert into \"GiftViewer\" (gift_id, user_id) values (?, ?)")) {
            for (UUID viewer : form.viewerIds()) {
                s.setObject(1, giftId);
                s.setObject(2, viewer);
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    /** Gifts RLS shows, oldest first; claims attached when {@code withClaims}, otherwise null (hidden). */
    private static List<Gift> gifts(Connection c, UUID me, UUID wishlistId, Map<UUID, Set<UUID>> viewers,
                                    boolean withClaims) throws SQLException {
        record Row(UUID id, String name, BigDecimal price, List<String> links, int quantity, String notes,
                   boolean visibleToAll, boolean removed) {
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement s = prepare(c, """
                select id, name, price, links, quantity, notes, is_visible_to_all, removed_at is not null as removed
                from "Gift"
                where wishlist_id = ?
                order by created_at, id""", wishlistId);
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                rows.add(new Row(r.getObject("id", UUID.class), r.getString("name"), r.getBigDecimal("price"),
                        links(r.getArray("links")), r.getInt("quantity"), r.getString("notes"),
                        r.getBoolean("is_visible_to_all"), r.getBoolean("removed")));
            }
        }
        Map<UUID, GiftClaims> claims = withClaims
                ? ClaimQueries.forGifts(c, me, rows.stream().map(Row::id).toList())
                : Map.of();
        return rows.stream().map(row -> new Gift(row.id(), row.name(), row.price(), row.links(), row.quantity(),
                row.notes(), row.visibleToAll(), Set.copyOf(viewers.getOrDefault(row.id(), Set.of())), row.removed(),
                withClaims ? claims.get(row.id()) : null)).toList();
    }

    private static List<String> links(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    /** Viewer ids per gift. RLS returns rows only to the wishlist owner. */
    private static Map<UUID, Set<UUID>> viewers(Connection c, UUID wishlistId) throws SQLException {
        try (PreparedStatement s = prepare(c, """
                select v.gift_id, v.user_id
                from "GiftViewer" v
                join "Gift" g on g.id = v.gift_id
                where g.wishlist_id = ?""", wishlistId);
             ResultSet r = s.executeQuery()) {
            Map<UUID, Set<UUID>> viewers = new HashMap<>();
            while (r.next()) {
                viewers.computeIfAbsent(r.getObject("gift_id", UUID.class), k -> new HashSet<>())
                        .add(r.getObject("user_id", UUID.class));
            }
            return viewers;
        }
    }

    /** Group members other than the owner, by name. */
    private static List<ViewerOption> shareableMembers(Connection c, UUID groupId, UUID ownerId) throws SQLException {
        try (PreparedStatement s = prepare(c, """
                select p.id, coalesce(p.display_name, p.username::text) as name
                from "GroupUser" gu
                join "Profile" p on p.id = gu.user_id
                where gu.group_id = ? and gu.user_id <> ?
                order by lower(coalesce(p.display_name, p.username::text)), p.id""", groupId, ownerId);
             ResultSet r = s.executeQuery()) {
            List<ViewerOption> members = new ArrayList<>();
            while (r.next()) {
                members.add(new ViewerOption(r.getObject("id", UUID.class), r.getString("name")));
            }
            return members;
        }
    }

    /** Runs a gift write; validation-trigger failures (bad viewers) become {@link BadRequestException}. */
    private <T> T save(JsonWebToken jwt, SqlWork<T> work) {
        try {
            return run(jwt, work);
        } catch (DataAccessException e) {
            if (Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.warnf("User %s submitted an invalid gift: %s", jwt.getSubject(), e.getCause().getMessage());
                throw new BadRequestException("Gifts can only be shared with members of the group");
            }
            throw e;
        }
    }

    /** Calls a {@code private.*} function that raises 42501 when not allowed. */
    private void call(JsonWebToken jwt, String sql, Object... params) {
        run(jwt, c -> {
            try (PreparedStatement s = prepare(c, sql, params)) {
                s.execute();
                return null;
            }
        });
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
