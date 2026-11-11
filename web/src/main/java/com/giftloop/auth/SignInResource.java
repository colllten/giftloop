package com.giftloop.auth;

import com.giftloop.security.SessionCookies;
import com.giftloop.supabase.SupabaseAuthService;
import com.giftloop.supabase.SupabaseException;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.CookieParam;
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
 * Sign-in with email and password (default) or an emailed 6-digit code, and sign-out.
 * Form steps are HTMX fragments swapped into {@code #auth-step}. Account creation
 * lives in {@link SignUpResource}; password reset in {@link PasswordResetResource}.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class SignInResource {

    private static final Logger LOG = Logger.getLogger(SignInResource.class);

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
    public SignInResource(SupabaseAuthService auth, SessionCookies cookies, SecurityIdentity identity) {
        this.auth = auth;
        this.cookies = cookies;
        this.identity = identity;
    }

    /**
     * Templates under {@code templates/SignInResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Sign-in page (email + password).
         *
         * @param next          where to go after sign-in
         * @param email         pre-filled email, may be null
         * @param emailError    message for the email field, may be null
         * @param passwordError message for the password field, may be null
         * @return page
         */
        static native TemplateInstance signin(String next, String email, String emailError, String passwordError);

        /**
         * Email + password form fragment.
         *
         * @param next          where to go after sign-in
         * @param email         pre-filled email, may be null
         * @param emailError    message for the email field, may be null
         * @param passwordError message for the password field, may be null
         * @return fragment
         */
        static native TemplateInstance passwordForm(String next, String email, String emailError, String passwordError);

        /**
         * "Email me a code" page.
         *
         * @param next  where to go after sign-in
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return page
         */
        static native TemplateInstance code(String next, String email, String error);

        /**
         * Email form fragment for requesting a code.
         *
         * @param next  where to go after sign-in
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return fragment
         */
        static native TemplateInstance codeEmailForm(String next, String email, String error);

        /**
         * Code entry fragment.
         *
         * @param next  where to go after sign-in
         * @param email address the code was sent to
         * @param error message for the code field, may be null
         * @return fragment
         */
        static native TemplateInstance codeStep(String next, String email, String error);
    }

    /**
     * Sign-in page. Signed-in users go straight to {@code next}.
     *
     * @param next    where to go after sign-in
     * @param headers request headers
     * @return page or redirect
     */
    @GET
    @Path("signin")
    @Operation(hidden = true)
    public Response page(@QueryParam("next") String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        if (!identity.isAnonymous()) {
            return Redirects.to(headers, safeNext).build();
        }
        return Response.ok(Templates.signin(safeNext, null, null, null)).build();
    }

    /**
     * Signs in with email and password.
     *
     * @param email    email address
     * @param password password
     * @param next     where to go after sign-in
     * @param headers  request headers
     * @return redirect with session cookies, or the form with an error
     */
    @POST
    @Path("signin/password")
    @Operation(hidden = true)
    public Response password(@RestForm String email, @RestForm String password, @RestForm String next,
                             HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Response.ok(Templates.passwordForm(safeNext, email, AuthForms.INVALID_EMAIL, null)).build();
        }
        if (password == null || password.isEmpty()) {
            return Response.ok(Templates.passwordForm(safeNext, normalized, null, AuthForms.MISSING_PASSWORD)).build();
        }
        try {
            return AuthForms.signedIn(cookies, auth.signInWithPassword(normalized, password), safeNext, headers);
        } catch (SupabaseException e) {
            LOG.infof("Password sign-in failed: %s", e.errorCode());
            return Response.ok(Templates.passwordForm(safeNext, normalized, null,
                    SupabaseAuthService.userMessage(e))).build();
        }
    }

    /**
     * "Email me a code" page. Signed-in users go straight to {@code next}.
     *
     * @param next    where to go after sign-in
     * @param headers request headers
     * @return page or redirect
     */
    @GET
    @Path("signin/code")
    @Operation(hidden = true)
    public Response codePage(@QueryParam("next") String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        if (!identity.isAnonymous()) {
            return Redirects.to(headers, safeNext).build();
        }
        return Response.ok(Templates.code(safeNext, null, null)).build();
    }

    /**
     * Emails a sign-in code to an existing account. The response is the same whether
     * or not the account exists.
     *
     * @param email email address
     * @param next  where to go after sign-in
     * @return code entry fragment, or the email form with an error
     */
    @POST
    @Path("signin/code")
    @Operation(hidden = true)
    public TemplateInstance sendCode(@RestForm String email, @RestForm String next) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Templates.codeEmailForm(safeNext, email, AuthForms.INVALID_EMAIL);
        }
        try {
            auth.sendSignInCode(normalized);
            return Templates.codeStep(safeNext, normalized, null);
        } catch (SupabaseException e) {
            LOG.warnf("Sending sign-in code failed: %s", e.getMessage());
            return Templates.codeEmailForm(safeNext, normalized, SupabaseAuthService.userMessage(e));
        }
    }

    /**
     * Verifies the emailed code and starts a session.
     *
     * @param email   address the code was sent to
     * @param code    6-digit code
     * @param next    where to go after sign-in
     * @param headers request headers
     * @return redirect with session cookies, or the code step with an error
     */
    @POST
    @Path("signin/code/verify")
    @Operation(hidden = true)
    public Response verifyCode(@RestForm String email, @RestForm String code, @RestForm String next,
                               HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Response.ok(Templates.codeEmailForm(safeNext, email, AuthForms.INVALID_EMAIL)).build();
        }
        String digits = AuthForms.normalizeCode(code);
        if (digits == null) {
            return Response.ok(Templates.codeStep(safeNext, normalized, AuthForms.INVALID_CODE)).build();
        }
        try {
            return AuthForms.signedIn(cookies, auth.verifySignInCode(normalized, digits), safeNext, headers);
        } catch (SupabaseException e) {
            LOG.infof("Code verification failed: %s", e.errorCode());
            return Response.ok(Templates.codeStep(safeNext, normalized, SupabaseAuthService.userMessage(e))).build();
        }
    }

    /**
     * Signs out: revokes the Supabase session and clears cookies.
     *
     * @param accessToken access cookie, may be null
     * @param headers     request headers
     * @return redirect home with cleared cookies
     */
    @POST
    @Path("signout")
    @Operation(hidden = true)
    public Response signOut(@CookieParam(SessionCookies.ACCESS) String accessToken, HttpHeaders headers) {
        if (accessToken != null && !accessToken.isBlank()) {
            auth.signOut(accessToken);
        }
        LOG.debug("Signed out");
        return Redirects.to(headers, "/").cookie(cookies.clear()).build();
    }
}
