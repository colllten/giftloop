package com.giftloop.enrich;

import java.time.Duration;

/**
 * The caller used up their auto-fills for now ({@code PT429} from
 * {@code private.record_link_preview}); auto-fill is refused until {@link #retryAfter()}.
 */
public class AutofillRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    /**
     * Creates the exception.
     *
     * @param retryAfter time until the user may try again
     */
    public AutofillRateLimitedException(Duration retryAfter) {
        super("Too many auto-fills; retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    /**
     * Time until the user may try again.
     *
     * @return positive duration
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
