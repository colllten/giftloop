package com.giftloop.security;

import com.giftloop.SupabaseAuthTestClient;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link RlsContext} applies the caller's identity so RLS rules hold,
 * using seed data: Bob claimed a gift on Alice's wishlist (Christmas 2026).
 */
@QuarkusTest
class RlsContextTest {

    @Inject
    RlsContext rls;

    @Inject
    JWTParser parser;

    @Inject
    AgroalDataSource dataSource;

    /** The wishlist owner cannot see claims on her own gifts before the event ends. */
    @Test
    void ownerCannotSeeClaimsOnOwnList() throws Exception {
        assertEquals(0, claimCount(token("alice")));
    }

    /** A claimer sees their claim. */
    @Test
    void claimerSeesClaim() throws Exception {
        assertEquals(1, claimCount(token("bob")));
    }

    /** A user outside the group sees nothing. */
    @Test
    void outsiderSeesNothing() throws Exception {
        assertEquals(0, claimCount(token("dave")));
    }

    /** Role and claims are transaction-local and do not leak to the pooled connection. */
    @Test
    void contextDoesNotLeakAfterTransaction() throws Exception {
        assertEquals("authenticated", rls.inUserTransaction(token("bob"), c -> scalar(c, "select current_user")));
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("postgres", scalar(connection, "select current_user"));
            assertEquals("", scalar(connection, "select coalesce(current_setting('request.jwt.claims', true), '')"));
        }
    }

    /** Database errors surface as {@link DataAccessException}. */
    @Test
    void sqlErrorsAreWrapped() throws Exception {
        JsonWebToken bob = token("bob");
        assertThrows(DataAccessException.class,
                () -> rls.inUserTransaction(bob, c -> scalar(c, "select * from no_such_table")));
    }

    /** Malformed tokens are rejected before touching the database. */
    @Test
    void claimsJsonRejectsMalformedToken() {
        assertThrows(IllegalArgumentException.class, () -> RlsContext.claimsJson("abc"));
        assertThrows(IllegalArgumentException.class, () -> RlsContext.claimsJson(null));
    }

    private JsonWebToken token(String user) throws Exception {
        return parser.parse(SupabaseAuthTestClient.accessToken(user));
    }

    private int claimCount(JsonWebToken jwt) {
        return Integer.parseInt(rls.inUserTransaction(jwt, c -> scalar(c, "select count(*) from \"Claim\"")));
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
