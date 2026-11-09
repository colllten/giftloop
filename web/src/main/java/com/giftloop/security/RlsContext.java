package com.giftloop.security;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Runs JDBC work as the calling user so Postgres RLS applies (ADR 001 / ADR 002).
 * <p>
 * Each call opens a transaction, sets {@code request.jwt.claims} to the verified
 * token's claims and switches to the {@code authenticated} role, both
 * transaction-local. This mirrors what PostgREST does, so {@code auth.uid()} and
 * every RLS policy behave identically. All user-facing DB access must go through here.
 */
@ApplicationScoped
public class RlsContext {

    private static final Logger LOG = Logger.getLogger(RlsContext.class);
    private static final String DB_ROLE = "authenticated";

    private final AgroalDataSource dataSource;

    /**
     * Creates the context.
     *
     * @param dataSource default datasource (local Supabase Postgres)
     */
    public RlsContext(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Executes {@code work} in a transaction scoped to the token's user.
     *
     * @param jwt  a token already verified by SmallRye JWT
     * @param work the JDBC work to run
     * @param <T>  result type
     * @return the work's result
     * @throws DataAccessException if the database call fails (transaction rolled back)
     */
    public <T> T inUserTransaction(JsonWebToken jwt, SqlWork<T> work) {
        Objects.requireNonNull(jwt, "jwt");
        Objects.requireNonNull(work, "work");
        String claimsJson = JwtPayload.json(jwt.getRawToken());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                applyUserContext(connection, claimsJson);
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.errorf(e, "User-scoped transaction failed for subject %s", jwt.getSubject());
            throw new DataAccessException("User-scoped database call failed", e);
        }
    }

    /**
     * Sets the transaction-local JWT claims and role used by RLS policies.
     *
     * @param connection connection with an open transaction
     * @param claimsJson the token payload as JSON
     * @throws SQLException on database errors
     */
    private static void applyUserContext(Connection connection, String claimsJson) throws SQLException {
        try (PreparedStatement claims = connection.prepareStatement(
                "select set_config('request.jwt.claims', ?, true)")) {
            claims.setString(1, claimsJson);
            claims.execute();
        }
        try (Statement role = connection.createStatement()) {
            role.execute("set local role " + DB_ROLE);
        }
    }
}
