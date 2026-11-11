package com.giftloop.auth;

import com.giftloop.security.SessionCookies;
import com.giftloop.supabase.SupabaseAuthService;
import com.giftloop.supabase.SupabaseException;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;

/**
 * Forgot password: email → 6-digit recovery code → choose a new password.
 * Verifying the code starts a session (as Supabase does), which then authorizes
 * the password change. Requesting a code looks the same for unknown emails.
 */
@Path("/signin/reset")
@Produces(MediaType.TEXT_HTML)
public class PasswordResetResource {

    private static final Logger LOG = Logger.getLogger(PasswordResetResource.class);

    private final SupabaseAuthService auth;
    private final SessionCookies cookies;
    private final JsonWebToken jwt;

    /**
     * Creates the resource.
     *
     * @param auth    Supabase Auth
     * @param cookies session cookie helper
     * @param jwt     caller's token (only present on authenticated requests)
     */
    public PasswordResetResource(SupabaseAuthService auth, SessionCookies cookies, JsonWebToken jwt) {
        this.auth = auth;
        this.cookies = cookies;
        this.jwt = jwt;
    }

    /**
     * Templates under {@code templates/PasswordResetResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Reset page (email step).
         *
         * @param next  where to go afterwards
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return page
         */
        static native TemplateInstance reset(String next, String email, String error);

        /**
         * Email form fragment.
         *
         * @param next  where to go afterwards
         * @param email pre-filled email, may be null
         * @param error message for the email field, may be null
         * @return fragment
         */
        static native TemplateInstance emailForm(String next, String email, String error);

        /**
         * Recovery code entry fragment.
         *
         * @param next  where to go afterwards
         * @param email address the code was sent to
         * @param error message for the code field, may be null
         * @return fragment
         */
        static native TemplateInstance codeStep(String next, String email, String error);

        /**
         * New password fragment.
         *
         * @param next  where to go afterwards
         * @param error message for the password field, may be null
         * @return fragment
         */
        static native TemplateInstance newPassword(String next, String error);
    }

    /**
     * Reset page. Available signed in or out.
     *
     * @param next where to go afterwards
     * @return page
     */
    @GET
    @Operation(hidden = true)
    public TemplateInstance page(@QueryParam("next") String next) {
        return Templates.reset(Redirects.safeNext(next), null, null);
    }

    /**
     * Emails a recovery code.
     *
     * @param email email address
     * @param next  where to go afterwards
     * @return code step, or the email form with an error
     */
    @POST
    @Operation(hidden = true)
    public TemplateInstance sendCode(@RestForm String email, @RestForm String next) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Templates.emailForm(safeNext, email, AuthForms.INVALID_EMAIL);
        }
        try {
            auth.sendPasswordResetCode(normalized);
            return Templates.codeStep(safeNext, normalized, null);
        } catch (SupabaseException e) {
            LOG.warnf("Sending reset code failed: %s", e.getMessage());
            return Templates.emailForm(safeNext, normalized, SupabaseAuthService.userMessage(e));
        }
    }

    /**
     * Verifies the recovery code, starts a session and asks for a new password.
     *
     * @param email address the code was sent to
     * @param code  6-digit code
     * @param next  where to go afterwards
     * @return new password step with session cookies, or the code step with an error
     */
    @POST
    @Path("verify")
    @Operation(hidden = true)
    public Response verifyCode(@RestForm String email, @RestForm String code, @RestForm String next) {
        String safeNext = Redirects.safeNext(next);
        String normalized = AuthForms.normalizeEmail(email);
        if (normalized == null) {
            return Response.ok(Templates.emailForm(safeNext, email, AuthForms.INVALID_EMAIL)).build();
        }
        String digits = AuthForms.normalizeCode(code);
        if (digits == null) {
            return Response.ok(Templates.codeStep(safeNext, normalized, AuthForms.INVALID_CODE)).build();
        }
        try {
            var session = auth.verifyPasswordResetCode(normalized, digits);
            LOG.debug("Reset code verified");
            return Response.ok(Templates.newPassword(safeNext, null)).cookie(cookies.issue(session)).build();
        } catch (SupabaseException e) {
            LOG.infof("Reset code verification failed: %s", e.errorCode());
            return Response.ok(Templates.codeStep(safeNext, normalized, SupabaseAuthService.userMessage(e))).build();
        }
    }

    /**
     * Sets the new password for the signed-in user.
     *
     * @param password new password
     * @param next     where to go afterwards
     * @param headers  request headers
     * @return redirect to {@code next}, or the new password step with an error
     */
    @POST
    @Path("password")
    @Authenticated
    @Operation(hidden = true)
    public Response setPassword(@RestForm String password, @RestForm String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        if (!AuthForms.isValidNewPassword(password)) {
            return Response.ok(Templates.newPassword(safeNext, AuthForms.SHORT_PASSWORD)).build();
        }
        try {
            auth.updatePassword(jwt.getRawToken(), password);
            return Redirects.to(headers, safeNext).build();
        } catch (SupabaseException e) {
            LOG.infof("Password change failed: %s", e.errorCode());
            return Response.ok(Templates.newPassword(safeNext, SupabaseAuthService.userMessage(e))).build();
        }
    }
}
