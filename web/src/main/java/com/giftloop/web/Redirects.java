package com.giftloop.web;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;

/**
 * Redirect helpers that work for both full page loads and HTMX requests.
 */
public final class Redirects {

    /** Header HTMX sets on its requests. */
    public static final String HX_REQUEST = "HX-Request";
    /** Response header that makes HTMX navigate. */
    public static final String HX_REDIRECT = "HX-Redirect";

    private Redirects() {
    }

    /**
     * Only same-site relative paths are allowed as redirect targets (prevents open redirects).
     *
     * @param next requested target
     * @return {@code next} if safe, else {@code "/"}
     */
    public static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")
                || next.contains("\r") || next.contains("\n")) {
            return "/";
        }
        return next;
    }

    /**
     * Redirects a browser: {@code HX-Redirect} for HTMX, 303 otherwise.
     *
     * @param headers request headers
     * @param target  relative path
     * @return response builder (add cookies before building)
     */
    public static Response.ResponseBuilder to(HttpHeaders headers, String target) {
        if (isHtmx(headers)) {
            return Response.ok().header(HX_REDIRECT, target);
        }
        return Response.seeOther(URI.create(target));
    }

    /**
     * Path with a {@code next} query parameter.
     *
     * @param path base path, e.g. {@code /signin}
     * @param next where to go afterwards
     * @return encoded path
     */
    public static String withNext(String path, String next) {
        String safe = safeNext(next);
        return "/".equals(safe) ? path : UriBuilder.fromPath(path).queryParam("next", safe).build().toString();
    }

    /**
     * Whether the request came from HTMX.
     *
     * @param headers request headers
     * @return true for HTMX requests
     */
    public static boolean isHtmx(HttpHeaders headers) {
        return "true".equals(headers.getHeaderString(HX_REQUEST));
    }
}
