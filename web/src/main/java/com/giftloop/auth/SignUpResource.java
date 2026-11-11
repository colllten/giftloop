package com.giftloop.auth;

import com.giftloop.security.SessionCookies;
import com.giftloop.supabase.SupabaseAuthService;
import com.giftloop.supabase.SupabaseException;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;

/**
 * Creates an email + password account and signs in immediately (no email
 * confirmation). New users then land on onboarding via {@code @RequiresOnboarding}.
 */
@Path("/signup")
@Produces(MediaType.TEXT_HTML)
public class SignUpResource {

    private static final Logger LOG = Logger.getLogger(SignUpResource.class);

    private final SupabaseAuthService auth;
    private final SessionCookies cookies;
    private final SecurityIdentity identity;

    /**
     * Creates the resource.
     *
     * @param auth     Supabase Auth
     * @param cookies  session cookie helper
     * @param identity current identity (resolved lazily)
     */
    public SignUpResource(SupabaseAuthService auth, SessionCookies cookies, SecurityIdentity identity) {
        this.auth = auth;
        this.cookies = cookies;
        this.identity = identity;
    }

    /**
     * Templates under {@code templates/SignUpResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Sign-up page.
         *
         * @param next          where to go after sign-up
         * @param email         pre-filled email, may be null
         * @param emailError    message for the email field, may be null
         * @param passwordError message for the password field, may be null
         * @return page
         */
        static native TemplateInstance signup(String next, String email, String emailError, String passwordError);

        /**
         * Sign-up form fragment.
         *
         * @param next          where to go after sign-up
         * @param email         pre-filled email, may be null
         * @param emailError    message for the email field, may be null
         * @param passwordError message for the password field, may be null
         * @return fragment
         */
        static native TemplateInstance signupForm(String next, String email, String emailError, String passwordError);
    }

    /**
     * Sign-up page. Signed-in users go straight to {@code next}.
     *
     * @param next    where to go after sign-up
     * @param headers request headers
     * @return page or redirect
     */
    @GET
    @Operation(hidden = true)
    public Response page(@QueryParam("next") String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        if (!identity.isAnonymous()) {
            return Redirects.to(headers, safeNext).build();
        }
        return Response.ok(Templates.signup(safeNext, null, null, null)).build();
    }

    /**
     * Creates the account and signs in.
     *
     * @param email    email address
     * @param password new password
     * @param next     where to go after sign-up
     * @param headers  request headers
     * @return redirect with session cookies, or the form with an error
     */
    @POST
    @Operation(hidden = true)
    public Response signUp(@RestForm String email, @RestForm String password, @RestForm String next,
                           HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Response.ok(Templates.signupForm(safeNext, email, AuthForms.INVALID_EMAIL, null)).build();
        }
        if (!AuthForms.isValidNewPassword(password)) {
            return Response.ok(Templates.signupForm(safeNext, normalized, null, AuthForms.SHORT_PASSWORD)).build();
        }
        try {
            return AuthForms.signedIn(cookies, auth.signUpWithPassword(normalized, password), safeNext, headers);
        } catch (SupabaseException e) {
            LOG.infof("Sign-up failed: %s", e.errorCode());
            String message = SupabaseAuthService.userMessage(e);
            boolean emailProblem = "user_already_exists".equals(e.errorCode()) || "email_exists".equals(e.errorCode())
                    || "email_address_invalid".equals(e.errorCode());
            return Response.ok(emailProblem
                    ? Templates.signupForm(safeNext, normalized, message, null)
                    : Templates.signupForm(safeNext, normalized, null, message)).build();
        }
    }
}
