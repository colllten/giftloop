package com.giftloop.security;

import com.giftloop.legal.LegalService;
import com.giftloop.profile.Profile;
import com.giftloop.profile.ProfileService;
import com.giftloop.web.Redirects;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * For resources annotated {@link RequiresOnboarding}, redirects signed-in users who haven't
 * accepted the current legal documents to {@code /legal/accept}, then those who haven't
 * finished onboarding to {@code /onboarding}.
 */
@Provider
@RequiresOnboarding
public class OnboardingGate implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(OnboardingGate.class);

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken jwt;

    @Inject
    ProfileService profiles;

    @Inject
    LegalService legal;

    @Inject
    HttpHeaders headers;

    /**
     * Aborts with a redirect when the caller is signed in but hasn't accepted the current
     * legal documents or isn't onboarded, and signs out callers whose account was deleted.
     *
     * @param request current request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        if (identity.isAnonymous()) {
            return;
        }
        Optional<Profile> profile = profiles.findOwn(jwt);
        if (profile.isEmpty()) {
            // Token still verifies, but the account was deleted (e.g. from another device).
            LOG.infof("Signing out %s: account no longer exists", jwt.getSubject());
            throw new UnauthorizedException();
        }
        String next = request.getUriInfo().getRequestUri().getRawPath();
        if (!legal.hasAcceptedCurrent(jwt)) {
            LOG.debugf("Redirecting %s to accept the legal documents", jwt.getSubject());
            request.abortWith(Redirects.to(headers, Redirects.withNext("/legal/accept", next)).build());
            return;
        }
        if (!profile.get().onboarded()) {
            LOG.debugf("Redirecting %s to onboarding", jwt.getSubject());
            request.abortWith(Redirects.to(headers, Redirects.withNext("/onboarding", next)).build());
        }
    }
}
