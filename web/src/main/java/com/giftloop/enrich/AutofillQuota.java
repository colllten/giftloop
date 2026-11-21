package com.giftloop.enrich;

import com.giftloop.observability.AppMetrics;
import com.giftloop.security.DataAccessException;
import com.giftloop.security.RlsContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.PreparedStatement;
import java.time.Duration;

import static com.giftloop.security.Sql.RATE_LIMITED;
import static com.giftloop.security.Sql.retryAfter;
import static com.giftloop.security.Sql.sqlState;

/**
 * Per-user auto-fill allowance: {@value #LIMIT} link previews per sliding hour, counted in the DB
 * by {@code private.record_link_preview} so it holds across restarts and instances. Every attempt
 * that would fetch counts, whether or not the preview succeeds.
 */
@ApplicationScoped
public class AutofillQuota {

    /** Mirrors {@code c_limit} in {@code private.record_link_preview}. */
    public static final int LIMIT = 20;
    /** Mirrors {@code c_window} in {@code private.record_link_preview}. */
    static final Duration WINDOW = Duration.ofHours(1);
    private static final Logger LOG = Logger.getLogger(AutofillQuota.class);

    private final RlsContext rls;
    private final AppMetrics metrics;

    /**
     * Creates the quota.
     *
     * @param rls     user-scoped DB access
     * @param metrics app metrics
     */
    public AutofillQuota(RlsContext rls, AppMetrics metrics) {
        this.rls = rls;
        this.metrics = metrics;
    }

    /**
     * Records one auto-fill attempt for the caller, or refuses it if they are over the limit.
     *
     * @param jwt caller's verified token
     * @throws AutofillRateLimitedException if the caller already used {@value #LIMIT} in the last hour
     */
    public void consume(JsonWebToken jwt) {
        try {
            rls.inUserTransaction(jwt, c -> {
                try (PreparedStatement s = c.prepareStatement("select private.record_link_preview()")) {
                    return s.execute();
                }
            });
        } catch (DataAccessException e) {
            if (RATE_LIMITED.equals(sqlState(e))) {
                Duration retryAfter = retryAfter(e, WINDOW);
                LOG.warnf("User %s hit the auto-fill limit; retry after %s", jwt.getSubject(), retryAfter);
                metrics.rateLimited(AppMetrics.Limit.AUTOFILL);
                throw new AutofillRateLimitedException(retryAfter);
            }
            throw e;
        }
    }
}
