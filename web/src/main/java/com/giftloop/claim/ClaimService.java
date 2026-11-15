package com.giftloop.claim;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.giftloop.security.Sql.firstLine;
import static com.giftloop.security.Sql.prepare;
import static com.giftloop.security.Sql.sqlState;

/**
 * Claims as the calling user. RLS and triggers enforce the rules: non-owner members who can see a
 * live gift claim it; the creator changes quantity, adds/removes co-buyers and releases; any
 * participant marks units bought, toggles their own anonymity or leaves; owners don't see claims before the reveal.
 *
 * <p>Deviation from layering: throws JAX-RS {@link ForbiddenException}/{@link BadRequestException}
 * directly (like {@code WishlistService}), since the only callers are HTTP resources.
 */
@ApplicationScoped
public class ClaimService {

    private static final Logger LOG = Logger.getLogger(ClaimService.class);

    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param rls user-scoped DB access
     */
    public ClaimService(RlsContext rls) {
        this.rls = rls;
    }

    /**
     * Claims units of a gift. Anonymity is set in the same transaction, so the caller's name is
     * never visible on the claim.
     *
     * @param jwt    caller's verified token
     * @param giftId gift id
     * @param form   validated form
     * @return new claim id
     * @throws ForbiddenException if the caller may not claim it (owner, hidden, removed, not a member)
     */
    public UUID create(JsonWebToken jwt, UUID giftId, ClaimForm form) {
        UUID id;
        try {
            id = run(jwt, c -> {
                UUID claimId;
                try (PreparedStatement s = prepare(c,
                        "insert into \"Claim\" (gift_id, quantity) values (?, ?) returning id",
                        giftId, form.quantityValue());
                     ResultSet r = s.executeQuery()) {
                    r.next();
                    claimId = r.getObject(1, UUID.class);
                }
                if (form.anonymous()) {
                    try (PreparedStatement s = prepare(c, """
                            update "ClaimParticipant" set is_anonymous = true
                            where claim_id = ? and user_id = auth.uid()""", claimId)) {
                        s.executeUpdate();
                    }
                }
                return claimId;
            });
        } catch (DataAccessException e) {
            // Triggers reject owners, non-members and removed gifts before RLS does.
            if (Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.warnf("User %s may not claim gift %s: %s", jwt.getSubject(), giftId, e.getCause().getMessage());
                throw new ForbiddenException();
            }
            throw e;
        }
        LOG.infof("User %s claimed %d of gift %s (claim %s)", jwt.getSubject(), form.quantityValue(), giftId, id);
        return id;
    }

    /**
     * Changes how many units a claim covers (creator).
     *
     * @param jwt      caller's verified token
     * @param claimId  claim id
     * @param quantity new quantity (validated)
     * @throws ForbiddenException  if the caller did not create the claim
     * @throws BadRequestException if {@code quantity} is below the units already marked bought
     */
    public void updateQuantity(JsonWebToken jwt, UUID claimId, int quantity) {
        try {
            update(jwt, "update \"Claim\" set quantity = ? where id = ?", quantity, claimId);
        } catch (DataAccessException e) {
            if (Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.warnf("User %s tried to set claim %s below its purchased units", jwt.getSubject(), claimId);
                throw new BadRequestException("You've marked more than that as bought. Lower that first.");
            }
            throw e;
        }
        LOG.infof("User %s set claim %s to %d", jwt.getSubject(), claimId, quantity);
    }

    /**
     * Sets how many units of a claim are bought (any participant).
     *
     * @param jwt       caller's verified token
     * @param claimId   claim id
     * @param purchased units bought (validated as non-negative)
     * @throws ForbiddenException  if the caller is not on the claim
     * @throws BadRequestException if {@code purchased} exceeds the claimed quantity
     */
    public void setPurchased(JsonWebToken jwt, UUID claimId, int purchased) {
        try {
            run(jwt, c -> {
                try (PreparedStatement s = prepare(c, "select private.set_claim_purchased(?, ?)", claimId, purchased)) {
                    s.execute();
                    return null;
                }
            });
        } catch (DataAccessException e) {
            if (Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.warnf("User %s tried to mark %d bought on claim %s", jwt.getSubject(), purchased, claimId);
                throw new BadRequestException("You can't mark more than you claimed.");
            }
            throw e;
        }
        LOG.infof("User %s marked %d bought on claim %s", jwt.getSubject(), purchased, claimId);
    }

    /**
     * Shows or hides the caller's name on a claim they are on.
     *
     * @param jwt       caller's verified token
     * @param claimId   claim id
     * @param anonymous whether to hide the caller's name
     * @throws ForbiddenException if the caller is not on the claim
     */
    public void setAnonymous(JsonWebToken jwt, UUID claimId, boolean anonymous) {
        update(jwt, """
                update "ClaimParticipant" set is_anonymous = ?
                where claim_id = ? and user_id = auth.uid()""", anonymous, claimId);
        LOG.infof("User %s set anonymous=%s on claim %s", jwt.getSubject(), anonymous, claimId);
    }

    /**
     * Adds a co-buyer to a claim (creator). Adding someone already on it does nothing.
     *
     * @param jwt     caller's verified token
     * @param claimId claim id
     * @param userId  member to add
     * @throws ForbiddenException  if the caller did not create the claim
     * @throws BadRequestException if that member can't buy the gift (owner, not a member, can't see it)
     */
    public void addParticipant(JsonWebToken jwt, UUID claimId, UUID userId) {
        try {
            run(jwt, c -> {
                try (PreparedStatement s = prepare(c, """
                        insert into "ClaimParticipant" (claim_id, user_id) values (?, ?)
                        on conflict do nothing""", claimId, userId)) {
                    return s.executeUpdate();
                }
            });
        } catch (DataAccessException e) {
            if (Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.warnf("User %s could not add %s to claim %s: %s", jwt.getSubject(), userId, claimId,
                        e.getCause().getMessage());
                throw new BadRequestException("That person can't buy this gift.");
            }
            throw e;
        }
        LOG.infof("User %s added %s to claim %s", jwt.getSubject(), userId, claimId);
    }

    /**
     * Removes someone from a claim: the creator removing a co-buyer, or a co-buyer leaving.
     *
     * @param jwt     caller's verified token
     * @param claimId claim id
     * @param userId  participant to remove
     * @throws ForbiddenException  if the caller may not remove them
     * @throws BadRequestException if the creator tries to leave their own claim (release it instead)
     */
    public void removeParticipant(JsonWebToken jwt, UUID claimId, UUID userId) {
        try {
            update(jwt, "delete from \"ClaimParticipant\" where claim_id = ? and user_id = ?", claimId, userId);
        } catch (DataAccessException e) {
            if (Sql.CHECK_VIOLATION.equals(sqlState(e))) {
                LOG.warnf("User %s tried to leave their own claim %s", jwt.getSubject(), claimId);
                throw new BadRequestException("Release the claim instead.");
            }
            throw e;
        }
        LOG.infof("User %s removed %s from claim %s", jwt.getSubject(), userId, claimId);
    }

    /**
     * Releases a claim and drops its co-buyers (creator). A removed gift whose last claim this was
     * is purged by the database.
     *
     * @param jwt     caller's verified token
     * @param claimId claim id
     * @throws ForbiddenException if the caller did not create the claim
     */
    public void release(JsonWebToken jwt, UUID claimId) {
        update(jwt, "delete from \"Claim\" where id = ?", claimId);
        LOG.infof("User %s released claim %s", jwt.getSubject(), claimId);
    }

    /**
     * Every claim the caller is on, grouped by event: upcoming events (soonest first, undated last),
     * then past ones (most recent first).
     *
     * @param jwt caller's verified token
     * @return the caller's claims per event
     */
    public List<MyClaim.EventClaims> myClaims(JsonWebToken jwt) {
        UUID me = UUID.fromString(jwt.getSubject());
        List<MyClaim> claims = run(jwt, c -> {
            record Row(UUID claimId, int quantity, int purchased, boolean creator, UUID giftId, String giftName,
                       BigDecimal price, List<String> links, int wanted, boolean giftRemoved,
                       UUID wishlistId, boolean wishlistRemoved, String ownerName, UUID eventId, String eventName,
                       LocalDate endDate, boolean past, String groupName, boolean offList) {
            }
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("""
                    select c.id as claim_id, c.quantity, c.purchased_quantity,
                           private.is_claim_creator(c.id) as is_creator,
                           g.id as gift_id, g.name as gift_name, g.price, g.links, g.quantity as wanted,
                           g.removed_at is not null as gift_removed, g.is_off_list,
                           w.id as wishlist_id, w.removed_at is not null as wishlist_removed,
                           coalesce(op.display_name, op.username::text) as owner_name,
                           e.id as event_id, e.name as event_name, e.end_date,
                           coalesce(e.end_date < current_date, false) as is_past,
                           gr.name as group_name
                    from "ClaimParticipant" me
                    join "Claim" c on c.id = me.claim_id
                    join "Gift" g on g.id = c.gift_id
                    join "Wishlist" w on w.id = g.wishlist_id
                    join "Event" e on e.id = w.event_id
                    join "Group" gr on gr.id = e.group_id
                    left join "Profile" op on op.id = w.owner_id
                    where me.user_id = auth.uid()
                    order by is_past,
                             case when coalesce(e.end_date < current_date, false) then e.end_date end desc,
                             e.end_date nulls last, e.name, e.id, owner_name, g.created_at, g.id""");
                 ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    rows.add(new Row(r.getObject("claim_id", UUID.class), r.getInt("quantity"),
                            r.getInt("purchased_quantity"),
                            r.getBoolean("is_creator"), r.getObject("gift_id", UUID.class), r.getString("gift_name"),
                            r.getBigDecimal("price"), links(r.getArray("links")), r.getInt("wanted"),
                            r.getBoolean("gift_removed"), r.getObject("wishlist_id", UUID.class),
                            r.getBoolean("wishlist_removed"), ownerName(r.getString("owner_name")),
                            r.getObject("event_id", UUID.class), r.getString("event_name"),
                            r.getObject("end_date", LocalDate.class), r.getBoolean("is_past"),
                            r.getString("group_name"), r.getBoolean("is_off_list")));
                }
            }
            Map<UUID, List<Participant>> participants =
                    ClaimQueries.participants(c, me, rows.stream().map(Row::claimId).toList());
            return rows.stream().map(row -> new MyClaim(
                    new GiftClaim(row.claimId(), row.quantity(), row.purchased(),
                            participants.getOrDefault(row.claimId(), List.of()),
                            row.creator()),
                    row.giftId(), row.giftName(), row.price(), row.links(), row.wanted(), row.giftRemoved(),
                    row.wishlistId(), row.wishlistRemoved(), row.ownerName(), row.eventId(), row.eventName(),
                    row.endDate(), row.past(), row.groupName(), row.offList())).toList();
        });
        Map<UUID, MyClaim.EventClaims> byEvent = new LinkedHashMap<>();
        for (MyClaim claim : claims) {
            byEvent.compute(claim.eventId(), (id, existing) -> {
                List<MyClaim> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing.claims());
                list.add(claim);
                return new MyClaim.EventClaims(id, claim.eventName(), claim.groupName(), claim.endDate(),
                        claim.past(), List.copyOf(list));
            });
        }
        return List.copyOf(byEvent.values());
    }

    private static String ownerName(String name) {
        return name == null ? ClaimQueries.FORMER_MEMBER : name;
    }

    private static List<String> links(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    /** Runs an update/delete; zero affected rows means RLS filtered it out. */
    private void update(JsonWebToken jwt, String sql, Object... params) {
        int rows = run(jwt, c -> {
            try (PreparedStatement s = prepare(c, sql, params)) {
                return s.executeUpdate();
            }
        });
        if (rows == 0) {
            LOG.warnf("User %s was not allowed to: %s", jwt.getSubject(), firstLine(sql));
            throw new ForbiddenException();
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
