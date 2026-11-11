package com.giftloop.group;

import java.time.Duration;

/**
 * The caller entered too many incorrect join codes recently ({@code PT429} from
 * {@code private.join_group_by_code}); every join attempt is refused until {@link #retryAfter()}.
 */
public class JoinRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    /**
     * Creates the exception.
     *
     * @param retryAfter time until the user may try again
     */
    public JoinRateLimitedException(Duration retryAfter) {
        super("Too many incorrect join codes; retry after " + retryAfter);
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
