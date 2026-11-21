package com.giftloop.security;

import io.vertx.core.MultiMap;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Adds browser security headers to every app response (docs/SECURITY.md).
 * <p>
 * The CSP allows only same-origin scripts, styles, fonts and requests, plus {@code data:}
 * images (staged gift photos are previewed as data URLs). Templates must therefore not use
 * inline {@code <script>}, {@code style=""} or {@code on*=} attributes, nor {@code hx-on}.
 * HTMX's indicator CSS is a constructed stylesheet, which CSP doesn't block.
 * <p>
 * {@code /q/} is skipped so the dev-mode Dev UI (inline scripts) keeps working; in
 * production nothing is served there on the public port (ADR 006).
 */
@ApplicationScoped
public class SecurityHeaders {

    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "manifest-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");
    static final String PERMISSIONS = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";
    /** One year. No includeSubDomains/preload: other subdomains of the final domain are unknown yet. */
    static final String HSTS = "max-age=31536000";

    private final boolean hsts;

    /**
     * Creates the handler.
     *
     * @param hsts whether to send Strict-Transport-Security (on in prod, where TLS is assumed)
     */
    public SecurityHeaders(@ConfigProperty(name = "giftloop.security.hsts", defaultValue = "true") boolean hsts) {
        this.hsts = hsts;
    }

    /**
     * Registers the handler ahead of every other route, so error and static responses get
     * the headers too.
     *
     * @param router the HTTP router
     */
    void register(@Observes Router router) {
        router.route().order(-2000).handler(this::handle);
    }

    /**
     * Sets the headers, then continues the request.
     *
     * @param rc routing context
     */
    void handle(RoutingContext rc) {
        if (!rc.normalizedPath().startsWith("/q/")) {
            MultiMap headers = rc.response().headers();
            headers.set("Content-Security-Policy", CSP);
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "same-origin");
            headers.set("Permissions-Policy", PERMISSIONS);
            headers.set("Cross-Origin-Opener-Policy", "same-origin");
            if (hsts) {
                headers.set("Strict-Transport-Security", HSTS);
            }
        }
        rc.next();
    }
}
