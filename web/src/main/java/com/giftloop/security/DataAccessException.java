package com.giftloop.security;

/**
 * Unchecked wrapper for database failures raised from {@link RlsContext}.
 */
public class DataAccessException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what was being attempted
     * @param cause   underlying database error
     */
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
