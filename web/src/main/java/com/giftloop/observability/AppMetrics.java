package com.giftloop.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;

/**
 * App-level Prometheus metrics (ADR 006). Call sites use these intention-revealing
 * methods rather than the registry, so names and tags stay consistent.
 * <p>
 * Tags are fixed enums only: never user, group, email or URL values (cardinality and privacy).
 */
@ApplicationScoped
public class AppMetrics {

    /** Rate limits enforced in Postgres ({@code PT429}). */
    public enum Limit { JOIN, AUTOFILL }

    /** Flows that email a one-time code. */
    public enum CodeFlow { SIGNIN, SIGNUP, RESET }

    /** Ways a session is created. */
    public enum SignInMethod { CODE, PASSWORD, RESET }

    /** Core user actions, counted after they succeed. */
    public enum Action { GROUP_CREATED, GROUP_JOINED, WISHLIST_CREATED, GIFT_ADDED, CLAIM_CREATED, EXCHANGE_DRAWN }

    private final MeterRegistry registry;

    /**
     * Creates the facade.
     *
     * @param registry Micrometer registry (Prometheus)
     */
    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Counts a request rejected by a rate limit.
     *
     * @param limit which limit
     */
    public void rateLimited(Limit limit) {
        registry.counter("giftloop.rate_limited", "limit", tag(limit)).increment();
    }

    /**
     * Counts a one-time code request.
     *
     * @param flow    which flow sent it
     * @param success whether Supabase accepted the request
     */
    public void codeSent(CodeFlow flow, boolean success) {
        registry.counter("giftloop.auth.codes_sent", "flow", tag(flow), "outcome", outcome(success)).increment();
    }

    /**
     * Counts a sign-in attempt.
     *
     * @param method  how the user signed in
     * @param success whether a session was issued
     */
    public void signIn(SignInMethod method, boolean success) {
        registry.counter("giftloop.auth.signins", "method", tag(method), "outcome", outcome(success)).increment();
    }

    /**
     * Counts a session refresh.
     *
     * @param success whether new tokens were issued
     */
    public void sessionRefresh(boolean success) {
        registry.counter("giftloop.auth.refreshes", "outcome", outcome(success)).increment();
    }

    /**
     * Counts a successful core action.
     *
     * @param action what happened
     */
    public void action(Action action) {
        registry.counter("giftloop.actions", "action", tag(action)).increment();
    }

    /**
     * Starts timing an auto-fill lookup; finish with {@link #autofillDone}.
     *
     * @return running sample
     */
    public Timer.Sample autofillStarted() {
        return Timer.start(registry);
    }

    /**
     * Records a finished auto-fill lookup.
     *
     * @param sample  from {@link #autofillStarted()}
     * @param outcome fixed outcome name (e.g. a result enum)
     */
    public void autofillDone(Timer.Sample sample, Enum<?> outcome) {
        sample.stop(Timer.builder("giftloop.autofill").tag("outcome", tag(outcome)).register(registry));
    }

    /**
     * Counts an error no handler expected (mapped to a 500 page).
     *
     * @param exceptionType exception's simple class name
     */
    public void unhandledError(String exceptionType) {
        registry.counter("giftloop.errors.unhandled", "exception", exceptionType).increment();
    }

    /**
     * Counts a WARN or ERROR log line from GiftLoop code.
     *
     * @param level  {@code warn} or {@code error}
     * @param logger logger category
     */
    void logEvent(String level, String logger) {
        registry.counter("giftloop.log.events", "level", level, "logger", logger).increment();
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String outcome(boolean success) {
        return success ? "success" : "failure";
    }
}
