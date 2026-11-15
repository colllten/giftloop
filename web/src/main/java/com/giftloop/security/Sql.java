package com.giftloop.security;

import org.jboss.logging.Logger;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Small JDBC helpers shared by the feature services.
 */
public final class Sql {

    /** SQLSTATE for an RLS or privilege denial. */
    public static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** SQLSTATE raised by check constraints and validation triggers. */
    public static final String CHECK_VIOLATION = "23514";
    /** SQLSTATE raised by rate-limited {@code private.*} functions; DETAIL holds seconds until retry. */
    public static final String RATE_LIMITED = "PT429";

    private static final Logger LOG = Logger.getLogger(Sql.class);

    private Sql() {
    }

    /**
     * Prepares a statement and binds positional parameters with {@code setObject}.
     *
     * @param c      connection
     * @param sql    statement text
     * @param params values for {@code ?} placeholders, in order
     * @return prepared statement (caller closes it)
     * @throws SQLException if preparing or binding fails
     */
    public static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            s.setObject(i + 1, params[i]);
        }
        return s;
    }

    /**
     * SQLSTATE of the SQL error behind a {@link DataAccessException}.
     *
     * @param e wrapped failure
     * @return SQLSTATE, or null if the cause is not a {@link SQLException}
     */
    public static String sqlState(DataAccessException e) {
        return e.getCause() instanceof SQLException sql ? sql.getSQLState() : null;
    }

    /**
     * Time until retry, from a {@link #RATE_LIMITED} error's DETAIL (seconds).
     *
     * @param e        wrapped {@code PT429} failure
     * @param fallback used if the detail is missing or not a number
     * @return time until the caller may try again
     */
    public static Duration retryAfter(DataAccessException e, Duration fallback) {
        if (e.getCause() instanceof PSQLException pg && pg.getServerErrorMessage() != null) {
            String detail = pg.getServerErrorMessage().getDetail();
            try {
                return Duration.ofSeconds(Long.parseLong(detail));
            } catch (NumberFormatException ignored) {
                LOG.warnf("Unexpected rate limit detail: %s", detail);
            }
        }
        return fallback;
    }

    /**
     * First line of a statement, for log messages.
     *
     * @param sql statement text
     * @return first non-blank line
     */
    public static String firstLine(String sql) {
        return sql.strip().lines().findFirst().orElse(sql);
    }
}
