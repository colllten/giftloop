package com.giftloop.claim;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claim reads shared by {@link ClaimService} and {@code WishlistService}, run on a connection already
 * inside {@code RlsContext.inUserTransaction}. RLS decides which claims and participants come back.
 *
 * <p>{@code Claim.created_by} is not readable, so columns are listed explicitly and
 * {@code private.is_claim_creator} tells whether the caller created a claim.
 */
public final class ClaimQueries {

    /** Shown for a participant whose profile the caller can no longer see (they left every shared group). */
    static final String FORMER_MEMBER = "Former member";

    private ClaimQueries() {
    }

    /**
     * Claims on the given gifts, keyed by gift id. Gifts without visible claims map to
     * {@link GiftClaims#NONE}.
     *
     * @param c       connection in a user transaction
     * @param me      caller's user id
     * @param giftIds gifts to load claims for
     * @return claims per gift, for every requested gift
     * @throws SQLException on database errors
     */
    public static Map<UUID, GiftClaims> forGifts(Connection c, UUID me, Collection<UUID> giftIds) throws SQLException {
        Map<UUID, GiftClaims> result = new HashMap<>();
        giftIds.forEach(id -> result.put(id, GiftClaims.NONE));
        if (giftIds.isEmpty()) {
            return result;
        }
        record Row(UUID id, UUID giftId, int quantity, boolean creator) {
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
                select c.id, c.gift_id, c.quantity, private.is_claim_creator(c.id) as is_creator
                from "Claim" c
                where c.gift_id = any(?)
                order by c.created_at, c.id""")) {
            s.setArray(1, c.createArrayOf("uuid", giftIds.toArray()));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    rows.add(new Row(r.getObject("id", UUID.class), r.getObject("gift_id", UUID.class),
                            r.getInt("quantity"), r.getBoolean("is_creator")));
                }
            }
        }
        Map<UUID, List<Participant>> participants = participants(c, me, rows.stream().map(Row::id).toList());
        Map<UUID, List<GiftClaim>> byGift = new LinkedHashMap<>();
        for (Row row : rows) {
            byGift.computeIfAbsent(row.giftId(), k -> new ArrayList<>()).add(new GiftClaim(row.id(), row.quantity(),
                    participants.getOrDefault(row.id(), List.of()), row.creator()));
        }
        byGift.forEach((giftId, claims) -> result.put(giftId, new GiftClaims(List.copyOf(claims))));
        return result;
    }

    /**
     * Visible participants per claim, the caller first, then by name.
     *
     * @param c        connection in a user transaction
     * @param me       caller's user id
     * @param claimIds claims to load
     * @return participants per claim id (claims without visible participants are absent)
     * @throws SQLException on database errors
     */
    static Map<UUID, List<Participant>> participants(Connection c, UUID me, Collection<UUID> claimIds)
            throws SQLException {
        Map<UUID, List<Participant>> result = new HashMap<>();
        if (claimIds.isEmpty()) {
            return result;
        }
        try (PreparedStatement s = c.prepareStatement("""
                select cp.claim_id, cp.user_id, cp.is_anonymous,
                       coalesce(p.display_name, p.username::text) as name
                from "ClaimParticipant" cp
                left join "Profile" p on p.id = cp.user_id
                where cp.claim_id = any(?)
                order by cp.user_id <> auth.uid(), lower(coalesce(p.display_name, p.username::text)), cp.user_id""")) {
            s.setArray(1, c.createArrayOf("uuid", claimIds.toArray()));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    UUID userId = r.getObject("user_id", UUID.class);
                    String name = r.getString("name");
                    result.computeIfAbsent(r.getObject("claim_id", UUID.class), k -> new ArrayList<>())
                            .add(new Participant(userId, name == null ? FORMER_MEMBER : name,
                                    r.getBoolean("is_anonymous"), userId.equals(me)));
                }
            }
        }
        return result;
    }
}
