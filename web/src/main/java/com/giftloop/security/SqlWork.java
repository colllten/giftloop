package com.giftloop.security;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Unit of JDBC work executed inside a user-scoped transaction.
 *
 * @param <T> result type
 */
@FunctionalInterface
public interface SqlWork<T> {

    /**
     * Runs the work on a connection that already has the caller's RLS context applied.
     * Do not commit, roll back or close the connection.
     *
     * @param connection connection inside an open transaction
     * @return the work's result
     * @throws SQLException on database errors (the transaction is rolled back)
     */
    T run(Connection connection) throws SQLException;
}
