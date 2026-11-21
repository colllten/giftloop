package com.giftloop.observability;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;

import java.util.logging.Level;

/**
 * Counts WARN and ERROR log lines from GiftLoop code as {@code giftloop.log.events}
 * (ADR 006). Handled failures are already logged at their call sites, so this tracks
 * them without touching those sites.
 */
@ApplicationScoped
public class ErrorLogMetrics {

    /** Logger whose subtree is counted. */
    static final String CATEGORY = "com.giftloop";

    private final AppMetrics metrics;
    private final CountingHandler handler = new CountingHandler();

    /**
     * Creates the bean.
     *
     * @param metrics app metrics
     */
    public ErrorLogMetrics(AppMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Attaches the counting handler at startup.
     *
     * @param event startup
     */
    void attach(@Observes StartupEvent event) {
        logger().addHandler(handler);
    }

    /**
     * Detaches it at shutdown (dev-mode reloads and tests restart the app in one JVM).
     *
     * @param event shutdown
     */
    void detach(@Observes ShutdownEvent event) {
        logger().removeHandler(handler);
    }

    private static Logger logger() {
        return LogContext.getLogContext().getLogger(CATEGORY);
    }

    /** Increments a counter per WARN/ERROR record; never formats or stores the message. */
    private final class CountingHandler extends ExtHandler {

        @Override
        protected void doPublish(ExtLogRecord record) {
            int level = record.getLevel().intValue();
            if (level >= Level.SEVERE.intValue()) {
                metrics.logEvent("error", record.getLoggerName());
            } else if (level >= Level.WARNING.intValue()) {
                metrics.logEvent("warn", record.getLoggerName());
            }
        }
    }
}
