package com.giftloop.auth;

import com.giftloop.legal.LegalAcceptResource;
import com.giftloop.legal.LegalService;
import com.giftloop.security.SessionCookies;
import com.giftloop.supabase.AuthSession;
import com.giftloop.supabase.SupabaseAuthService;
import com.giftloop.supabase.SupabaseException;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
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
 * Creates an account in two steps: email → emailed 6-digit code + new password.
 * Every email gets the same response (new emails a sign-up code, existing accounts a
 * sign-in code), so sign-up never reveals whether an account exists. The password is
 * set only after the code proves the email is theirs, so a password set by whoever
 * pre-registered an unconfirmed account is replaced. For an existing account this
 * works like a password reset. The code step requires agreeing to the Terms and Privacy
 * Policy, recorded once signed in. New users then land on onboarding via
 * {@code @RequiresOnboarding}.
 */
@Path("/signup")
@Produces(MediaType.TEXT_HTML)
public class SignUpResource {

    private static final Logger LOG = Logger.getLogger(SignUpResource.class);
    /** Supabase error when the new password equals the current one; harmless here. */
    private static final String SAME_PASSWORD = "same_password";

    private final SupabaseAuthService auth;
    private final SessionCookies cookies;
    private final SecurityIdentity identity;
    private final LegalService legal;
    private final JWTParser jwtParser;

    /**
     * Creates the resource.
     *
     * @param auth      Supabase Auth
     * @param cookies   session cookie helper
     * @param identity  current identity (resolved lazily)
     * @param legal     legal document acceptance
     * @param jwtParser verifies the new session's access token
     */
    public SignUpResource(SupabaseAuthService auth, SessionCookies cookies, SecurityIdentity identity,
                          LegalService legal, JWTParser jwtParser) {
        this.auth = auth;
        this.cookies = cookies;
        this.identity = identity;
        this.legal = legal;
        this.jwtParser = jwtParser;
    }

    /**
     * Templates under {@code templates/SignUpResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Sign-up page.
         *
         * @param next  where to go after sign-up
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return page
         */
        static native TemplateInstance signup(String next, String email, String error);

        /**
         * Email form fragment.
         *
         * @param next  where to go after sign-up
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return fragment
         */
        static native TemplateInstance signupForm(String next, String email, String error);

        /**
         * Code + new password fragment.
         *
         * @param next          where to go after sign-up
         * @param email         address the code was sent to
         * @param codeError     message for the code field, may be null
         * @param passwordError message for the password field, may be null
         * @param termsError    message for the Terms checkbox, may be null
         * @param acceptTerms   whether the Terms checkbox stays ticked
         * @return fragment
         */
        static native TemplateInstance signupCodeStep(String next, String email, String codeError,
                                                      String passwordError, String termsError,
                                                      boolean acceptTerms);
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
        return Response.ok(Templates.signup(safeNext, null, null)).build();
    }

    /**
     * Emails a code. The response is the same whether or not the account exists.
     *
     * @param email email address
     * @param next  where to go after sign-up
     * @return code step, or the email form with an error
     */
    @POST
    @Operation(hidden = true)
    public TemplateInstance sendCode(@RestForm String email, @RestForm String next) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Templates.signupForm(safeNext, email, AuthForms.INVALID_EMAIL);
        }
        try {
            auth.sendSignUpCode(normalized);
            return Templates.signupCodeStep(safeNext, normalized, null, null, null, false);
        } catch (SupabaseException e) {
            LOG.warnf("Sending sign-up code failed: %s", e.getMessage());
            return Templates.signupForm(safeNext, normalized, SupabaseAuthService.userMessage(e));
        }
    }

    /**
     * Verifies the code, sets the password, records Terms acceptance and signs in. Input
     * is checked before the code, so a rejected form doesn't spend it.
     *
     * @param email       address the code was sent to
     * @param code        6-digit code
     * @param password    new password
     * @param acceptTerms whether the Terms/Privacy checkbox was ticked
     * @param next        where to go after sign-up
     * @param headers     request headers
     * @return redirect with session cookies, or the code step with an error
     */
    @POST
    @Path("verify")
    @Operation(hidden = true)
    public Response verify(@RestForm String email, @RestForm String code, @RestForm String password,
                           @RestForm boolean acceptTerms, @RestForm String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Response.ok(Templates.signupForm(safeNext, email, AuthForms.INVALID_EMAIL)).build();
        }
        String digits = AuthForms.normalizeCode(code);
        if (digits == null) {
            return Response.ok(Templates.signupCodeStep(safeNext, normalized, AuthForms.INVALID_CODE, null, null,
                    acceptTerms)).build();
        }
        if (!AuthForms.isValidNewPassword(password)) {
            return Response.ok(Templates.signupCodeStep(safeNext, normalized, null, AuthForms.SHORT_PASSWORD, null,
                    acceptTerms)).build();
        }
        if (!acceptTerms) {
            return Response.ok(Templates.signupCodeStep(safeNext, normalized, null, null,
                    LegalAcceptResource.MUST_ACCEPT, false)).build();
        }
        AuthSession session;
        try {
            session = auth.verifySignInCode(normalized, digits);
        } catch (SupabaseException e) {
            LOG.infof("Sign-up code verification failed: %s", e.errorCode());
            return Response.ok(Templates.signupCodeStep(safeNext, normalized,
                    SupabaseAuthService.userMessage(e), null, null, true)).build();
        }
        try {
            auth.updatePassword(session.accessToken(), password);
        } catch (SupabaseException e) {
            if (!SAME_PASSWORD.equals(e.errorCode())) {
                // The code is spent; the user must request a new one.
                LOG.warnf("Setting sign-up password failed: %s", e.errorCode());
                auth.signOut(session.accessToken());
                return Response.ok(Templates.signupCodeStep(safeNext, normalized, null,
                        SupabaseAuthService.userMessage(e), null, true)).build();
            }
        }
        recordAcceptance(session);
        LOG.info("Sign-up completed");
        return AuthForms.signedIn(cookies, session, safeNext, headers);
    }

    /**
     * Records Terms acceptance for the new session. Failure is logged, not fatal:
     * {@code OnboardingGate} asks again before any gated page.
     *
     * @param session the signed-in session
     */
    private void recordAcceptance(AuthSession session) {
        try {
            legal.recordAcceptance(jwtParser.parse(session.accessToken()));
        } catch (ParseException | RuntimeException e) {
            LOG.warnf("Recording legal acceptance at sign-up failed: %s", e.getMessage());
        }
    }
}
