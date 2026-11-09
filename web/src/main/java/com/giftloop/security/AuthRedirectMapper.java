package com.giftloop.security;

import com.giftloop.web.Redirects;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.net.URI;

/**
 * Unauthenticated requests: {@code /api/**} gets 401; pages are sent to sign-in
 * and come back afterwards. A bad or expired session cookie is cleared.
 */
public class AuthRedirectMapper {

    private final SessionCookies cookies;

    /**
     * Creates the mapper.
     *
     * @param cookies session cookie helper
     */
    public AuthRedirectMapper(SessionCookies cookies) {
        this.cookies = cookies;
    }

    /**
     * Handles missing credentials.
     *
     * @param e       the failure
     * @param uriInfo request URI
     * @param headers request headers
     * @return 401 or a redirect to sign-in
     */
    @ServerExceptionMapper
    public Response unauthorized(UnauthorizedException e, UriInfo uriInfo, HttpHeaders headers) {
        return handle(uriInfo, headers);
    }

    /**
     * Handles invalid credentials (bad or expired token).
     *
     * @param e       the failure
     * @param uriInfo request URI
     * @param headers request headers
     * @return 401 or a redirect to sign-in
     */
    @ServerExceptionMapper
    public Response authenticationFailed(AuthenticationFailedException e, UriInfo uriInfo, HttpHeaders headers) {
        return handle(uriInfo, headers);
    }

    private Response handle(UriInfo uriInfo, HttpHeaders headers) {
        String path = uriInfo.getRequestUri().getRawPath();
        if (path.startsWith("/api/")) {
            return Response.status(Response.Status.UNAUTHORIZED).cookie(cookies.clear()).build();
        }
        String next = Redirects.isHtmx(headers) ? currentPage(headers) : relative(uriInfo.getRequestUri());
        return Redirects.to(headers, Redirects.withNext("/signin", next)).cookie(cookies.clear()).build();
    }

    private static String currentPage(HttpHeaders headers) {
        String current = headers.getHeaderString("HX-Current-URL");
        return current == null ? "/" : relative(URI.create(current));
    }

    private static String relative(URI uri) {
        return uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
    }
}
