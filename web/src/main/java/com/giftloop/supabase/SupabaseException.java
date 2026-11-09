package com.giftloop.supabase;

/**
 * Error response from a Supabase API (Auth or Storage).
 */
public class SupabaseException extends RuntimeException {

    private final int status;
    private final String errorCode;

    /**
     * Creates the exception.
     *
     * @param status    HTTP status returned by Supabase
     * @param errorCode Supabase error code (e.g. {@code invalid_credentials}), may be null
     * @param message   Supabase message (not shown to users verbatim)
     */
    public SupabaseException(int status, String errorCode, String message) {
        super("Supabase error " + status + " (" + errorCode + "): " + message);
        this.status = status;
        this.errorCode = errorCode;
    }

    /**
     * HTTP status from Supabase.
     *
     * @return status code
     */
    public int status() {
        return status;
    }

    /**
     * Supabase error code.
     *
     * @return error code, or null if none was given
     */
    public String errorCode() {
        return errorCode;
    }
}
