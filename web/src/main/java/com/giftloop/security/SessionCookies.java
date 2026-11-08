package com.giftloop.security;

import com.giftloop.supabase.AuthSession;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.NewCookie;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;

/**
 * Session cookies (ADR 003): {@value #ACCESS} holds the access token and expires
 * with it; {@value #REFRESH} holds the refresh token. Both HttpOnly, SameSite=Lax,
 * Secure unless disabled for local http.
 */
@ApplicationScoped
public class SessionCookies {

    /** Access token cookie name (also configured as SmallRye JWT's token cookie). */
    public static final String ACCESS = "gl_at";
    /** Refresh token cookie name. */
    public static final String REFRESH = "gl_rt";

    private final boolean secure;
    private final Duration refreshMaxAge;

    /**
     * Creates the helper.
     *
     * @param secure        whether to mark cookies Secure (false only for local http)
     * @param refreshMaxAge lifetime of the refresh cookie
     */
    public SessionCookies(@ConfigProperty(name = "giftloop.session.secure-cookies", defaultValue = "true") boolean secure,
                          @ConfigProperty(name = "giftloop.session.refresh-max-age", defaultValue = "P30D") Duration refreshMaxAge) {
        this.secure = secure;
        this.refreshMaxAge = refreshMaxAge;
    }

    /**
     * Cookies that start or renew a session (JAX-RS responses).
     *
     * @param session tokens from Supabase
     * @return access and refresh cookies
     */
    public NewCookie[] issue(AuthSession session) {
        return new NewCookie[]{
                jaxrs(ACCESS, session.accessToken(), (int) session.expiresIn()),
                jaxrs(REFRESH, session.refreshToken(), (int) refreshMaxAge.toSeconds())
        };
    }

    /**
     * Cookies that end a session (JAX-RS responses).
     *
     * @return expired access and refresh cookies
     */
    public NewCookie[] clear() {
        return new NewCookie[]{jaxrs(ACCESS, "", 0), jaxrs(REFRESH, "", 0)};
    }

    /**
     * Same as {@link #issue} for raw Vert.x responses.
     *
     * @param session tokens from Supabase
     * @return access and refresh cookies
     */
    public List<Cookie> issueVertx(AuthSession session) {
        return List.of(vertx(ACCESS, session.accessToken(), session.expiresIn()),
                vertx(REFRESH, session.refreshToken(), refreshMaxAge.toSeconds()));
    }

    /**
     * Same as {@link #clear} for raw Vert.x responses.
     *
     * @return expired access and refresh cookies
     */
    public List<Cookie> clearVertx() {
        return List.of(vertx(ACCESS, "", 0), vertx(REFRESH, "", 0));
    }

    private NewCookie jaxrs(String name, String value, int maxAge) {
        return new NewCookie.Builder(name)
                .value(value)
                .path("/")
                .maxAge(maxAge)
                .httpOnly(true)
                .secure(secure)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
    }

    private Cookie vertx(String name, String value, long maxAge) {
        return Cookie.cookie(name, value)
                .setPath("/")
                .setMaxAge(maxAge)
                .setHttpOnly(true)
                .setSecure(secure)
                .setSameSite(CookieSameSite.LAX);
    }
}
