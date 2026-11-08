package com.giftloop.security;

import com.giftloop.profile.Profile;
import com.giftloop.profile.ProfileService;
import com.giftloop.web.Redirects;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

/**
 * Redirects signed-in users who haven't finished onboarding to {@code /onboarding}
 * for resources annotated {@link RequiresOnboarding}.
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
    HttpHeaders headers;

    /**
     * Aborts with a redirect when the caller is signed in but not onboarded.
     *
     * @param request current request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        if (identity.isAnonymous()) {
            return;
        }
        boolean onboarded = profiles.findOwn(jwt).map(Profile::onboarded).orElse(false);
        if (!onboarded) {
            LOG.debugf("Redirecting %s to onboarding", jwt.getSubject());
            String next = request.getUriInfo().getRequestUri().getRawPath();
            request.abortWith(Redirects.to(headers, Redirects.withNext("/onboarding", next)).build());
        }
    }
}
