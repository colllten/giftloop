package com.giftloop.auth;

import com.giftloop.security.SessionCookies;
import com.giftloop.supabase.AuthSession;
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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email-first sign-in: enter email, then get a 6-digit code (default, also creates
 * accounts), sign in with a password, or create a password account. All steps
 * are HTMX fragments swapped into {@code #signin-step}.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class SignInResource {

    private static final Logger LOG = Logger.getLogger(SignInResource.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE = Pattern.compile("^\\d{6}$");
    private static final int EMAIL_MAX = 254;

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
         * Full sign-in page (email step).
         *
         * @param next  where to go after sign-in
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return page
         */
        static native TemplateInstance signin(String next, String email, String error);

        /**
         * Email step fragment.
         *
         * @param next  where to go after sign-in
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return fragment
         */
        static native TemplateInstance emailStep(String next, String email, String error);

        /**
         * Choose code / password / create account.
         *
         * @param next          where to go after sign-in
         * @param email         chosen email
         * @param passwordError error for the password sign-in form, may be null
         * @param signupError   error for the create-account form, may be null
         * @param open          which section to show expanded: {@code password}, {@code signup} or null
         * @return fragment
         */
        static native TemplateInstance choices(String next, String email, String passwordError,
                                               String signupError, String open);

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
        return Response.ok(Templates.signin(safeNext, null, null)).build();
    }

    /**
     * Validates the email and shows sign-in choices.
     *
     * @param email submitted email
     * @param next  where to go after sign-in
     * @return choices fragment, or email step with an error
     */
    @POST
    @Path("signin/email")
    @Operation(hidden = true)
    public TemplateInstance email(@RestForm String email, @RestForm String next) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return Templates.emailStep(Redirects.safeNext(next), email, "Please enter a valid email address.");
        }
        return Templates.choices(Redirects.safeNext(next), normalized, null, null, null);
    }

    /**
     * Emails a sign-in code.
     *
     * @param email chosen email
     * @param next  where to go after sign-in
     * @return code entry fragment, or email step with an error
     */
    @POST
    @Path("signin/code")
    @Operation(hidden = true)
    public TemplateInstance sendCode(@RestForm String email, @RestForm String next) {
        String safeNext = Redirects.safeNext(next);
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return Templates.emailStep(safeNext, email, "Please enter a valid email address.");
        }
        try {
            auth.sendSignInCode(normalized);
            return Templates.codeStep(safeNext, normalized, null);
        } catch (SupabaseException e) {
            LOG.warnf("Sending sign-in code failed: %s", e.getMessage());
            return Templates.emailStep(safeNext, normalized, SupabaseAuthService.userMessage(e));
        }
    }

    /**
     * Verifies the emailed code and starts a session.
     *
     * @param email   address the code was sent to
     * @param code    6-digit code
     * @param next    where to go after sign-in
     * @param headers request headers
     * @return redirect with session cookies, or code step with an error
     */
    @POST
    @Path("signin/code/verify")
    @Operation(hidden = true)
    public Response verifyCode(@RestForm String email, @RestForm String code, @RestForm String next,
                               HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        String normalized = normalizeEmail(email);
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        if (normalized == null) {
            return Response.ok(Templates.emailStep(safeNext, email, "Please enter a valid email address.")).build();
        }
        if (!CODE.matcher(digits).matches()) {
            return Response.ok(Templates.codeStep(safeNext, normalized, "Please enter the 6-digit code from the email.")).build();
        }
        try {
            return signedIn(auth.verifySignInCode(normalized, digits), safeNext, headers);
        } catch (SupabaseException e) {
            LOG.infof("Code verification failed: %s", e.errorCode());
            return Response.ok(Templates.codeStep(safeNext, normalized, SupabaseAuthService.userMessage(e))).build();
        }
    }

    /**
     * Signs in with a password.
     *
     * @param email    chosen email
     * @param password password
     * @param next     where to go after sign-in
     * @param headers  request headers
     * @return redirect with session cookies, or choices with an error
     */
    @POST
    @Path("signin/password")
    @Operation(hidden = true)
    public Response password(@RestForm String email, @RestForm String password, @RestForm String next,
                             HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return Response.ok(Templates.emailStep(safeNext, email, "Please enter a valid email address.")).build();
        }
        if (password == null || password.isEmpty()) {
            return Response.ok(Templates.choices(safeNext, normalized, "Please enter your password.", null, "password")).build();
        }
        try {
            return signedIn(auth.signInWithPassword(normalized, password), safeNext, headers);
        } catch (SupabaseException e) {
            LOG.infof("Password sign-in failed: %s", e.errorCode());
            return Response.ok(Templates.choices(safeNext, normalized, SupabaseAuthService.userMessage(e), null, "password")).build();
        }
    }

    /**
     * Creates a password account and signs in.
     *
     * @param email    chosen email
     * @param password new password
     * @param next     where to go after sign-in
     * @param headers  request headers
     * @return redirect with session cookies, or choices with an error
     */
    @POST
    @Path("signup/password")
    @Operation(hidden = true)
    public Response signUp(@RestForm String email, @RestForm String password, @RestForm String next,
                           HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return Response.ok(Templates.emailStep(safeNext, email, "Please enter a valid email address.")).build();
        }
        if (password == null || password.length() < 8) {
            return Response.ok(Templates.choices(safeNext, normalized, null,
                    "Please choose a password with at least 8 characters.", "signup")).build();
        }
        try {
            return signedIn(auth.signUpWithPassword(normalized, password), safeNext, headers);
        } catch (SupabaseException e) {
            LOG.infof("Password sign-up failed: %s", e.errorCode());
            return Response.ok(Templates.choices(safeNext, normalized, null, SupabaseAuthService.userMessage(e), "signup")).build();
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

    private Response signedIn(AuthSession session, String next, HttpHeaders headers) {
        LOG.debug("Signed in");
        return Redirects.to(headers, next).cookie(cookies.issue(session)).build();
    }

    /**
     * Trims and lower-cases an email; returns null if it's not plausibly valid.
     *
     * @param email raw input
     * @return normalized email or null
     */
    static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.strip().toLowerCase(Locale.ROOT);
        return trimmed.length() <= EMAIL_MAX && EMAIL.matcher(trimmed).matches() ? trimmed : null;
    }
}
