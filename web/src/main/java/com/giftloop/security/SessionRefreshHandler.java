package com.giftloop.security;

import com.giftloop.supabase.SupabaseAuthService;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

/**
 * Keeps cookie sessions alive (ADR 003). Before any request is handled, if the
 * access cookie is missing or expires within {@link #REFRESH_WINDOW} and a
 * refresh cookie exists, exchanges the refresh token, sets new cookies on the
 * response, and passes the new access token to this request via the
 * {@code Authorization} header. If refresh fails, both cookies are cleared.
 * <p>
 * Runs as a Vert.x route (non-blocking) so it happens before lazy authentication.
 */
@ApplicationScoped
public class SessionRefreshHandler {

    private static final Logger LOG = Logger.getLogger(SessionRefreshHandler.class);
    static final Duration REFRESH_WINDOW = Duration.ofMinutes(5);

    private final SupabaseAuthService auth;
    private final SessionCookies cookies;

    /**
     * Creates the handler.
     *
     * @param auth    Supabase Auth
     * @param cookies session cookie helper
     */
    public SessionRefreshHandler(SupabaseAuthService auth, SessionCookies cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    /**
     * Registers the handler ahead of application routes.
     *
     * @param router the HTTP router
     */
    void register(@Observes Router router) {
        router.route().order(-1000).handler(this::handle);
    }

    /**
     * Refreshes the session if needed, then continues the request.
     *
     * @param rc routing context
     */
    void handle(RoutingContext rc) {
        String path = rc.normalizedPath();
        Cookie refresh = rc.request().getCookie(SessionCookies.REFRESH);
        if (refresh == null || refresh.getValue().isBlank() || isExcluded(path)
                || rc.request().headers().contains("Authorization")) {
            rc.next();
            return;
        }
        Cookie access = rc.request().getCookie(SessionCookies.ACCESS);
        if (access != null && !needsRefresh(access.getValue(), Instant.now())) {
            rc.next();
            return;
        }

        auth.refresh(refresh.getValue()).subscribe().with(
                session -> {
                    LOG.debug("Session refreshed");
                    cookies.issueVertx(session).forEach(rc.response()::addCookie);
                    stripAccessCookie(rc);
                    rc.request().headers().set("Authorization", "Bearer " + session.accessToken());
                    rc.next();
                },
                failure -> {
                    LOG.infof("Session refresh failed, signing out: %s", failure.getMessage());
                    cookies.clearVertx().forEach(rc.response()::addCookie);
                    stripAccessCookie(rc);
                    rc.next();
                });
    }

    /**
     * True if the access token is unreadable or expires within the refresh window.
     *
     * @param accessToken raw access token from the cookie
     * @param now         current time
     * @return whether to refresh
     */
    static boolean needsRefresh(String accessToken, Instant now) {
        return JwtPayload.expiry(accessToken)
                .map(exp -> exp.isBefore(now.plus(REFRESH_WINDOW)))
                .orElse(true);
    }

    /**
     * Removes the stale access cookie from this request so authentication only
     * sees the refreshed token (or none). Other cookies, e.g. CSRF, are kept.
     *
     * @param rc routing context
     */
    private static void stripAccessCookie(RoutingContext rc) {
        String header = rc.request().getHeader("Cookie");
        if (header == null) {
            return;
        }
        String kept = java.util.Arrays.stream(header.split(";"))
                .map(String::trim)
                .filter(c -> !c.startsWith(SessionCookies.ACCESS + "="))
                .collect(java.util.stream.Collectors.joining("; "));
        rc.request().headers().set("Cookie", kept);
    }

    /**
     * Static assets and Quarkus endpoints never need a session.
     *
     * @param path normalized request path
     * @return true to skip refresh
     */
    private static boolean isExcluded(String path) {
        return path.startsWith("/_static/") || path.startsWith("/assets/") || path.startsWith("/q/")
                || path.equals("/signout");
    }
}
