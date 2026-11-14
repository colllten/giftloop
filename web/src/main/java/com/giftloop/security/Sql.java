package com.giftloop.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Small JDBC helpers shared by the feature services.
 */
public final class Sql {

    /** SQLSTATE for an RLS or privilege denial. */
    public static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** SQLSTATE raised by check constraints and validation triggers. */
    public static final String CHECK_VIOLATION = "23514";

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
     * First line of a statement, for log messages.
     *
     * @param sql statement text
     * @return first non-blank line
     */
    public static String firstLine(String sql) {
        return sql.strip().lines().findFirst().orElse(sql);
    }
}
