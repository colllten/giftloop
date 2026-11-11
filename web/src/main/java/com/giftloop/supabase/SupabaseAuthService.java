package com.giftloop.supabase;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Sign-up, sign-in (password or emailed code), password reset, refresh and sign-out
 * against Supabase Auth.
 * Failures surface as {@link SupabaseException}; {@link #userMessage} gives friendly text.
 */
@ApplicationScoped
public class SupabaseAuthService {

    private static final Logger LOG = Logger.getLogger(SupabaseAuthService.class);

    /** Supabase error when {@code /otp} is called with {@code create_user=false} for an unknown email. */
    static final String OTP_NO_ACCOUNT = "otp_disabled";

    private final SupabaseAuthApi api;

    /**
     * Creates the service.
     *
     * @param api Supabase Auth REST client
     */
    public SupabaseAuthService(@RestClient SupabaseAuthApi api) {
        this.api = api;
    }

    /**
     * Creates a password account and signs in.
     *
     * @param email    email address
     * @param password password (Supabase enforces minimum length)
     * @return session
     * @throws SupabaseException on failure, or if no session was returned
     */
    public AuthSession signUpWithPassword(String email, String password) {
        AuthSession session = api.signUp(Map.of("email", email, "password", password));
        if (session == null || !session.isComplete()) {
            throw new SupabaseException(400, "no_session", "Sign-up did not return a session");
        }
        LOG.info("Password account created");
        return session;
    }

    /**
     * Signs in with email and password.
     *
     * @param email    email address
     * @param password password
     * @return session
     */
    public AuthSession signInWithPassword(String email, String password) {
        return api.token("password", Map.of("email", email, "password", password));
    }

    /**
     * Emails a 6-digit sign-in code to an existing account. Never creates accounts.
     * Unknown emails are ignored silently so callers can't tell whether an account exists.
     *
     * @param email email address
     */
    public void sendSignInCode(String email) {
        try {
            api.sendOtp(Map.of("email", email, "create_user", false));
            LOG.debug("Sign-in code sent");
        } catch (SupabaseException e) {
            if (!OTP_NO_ACCOUNT.equals(e.errorCode())) {
                throw e;
            }
            LOG.debug("Sign-in code requested for unknown email; ignored");
        }
    }

    /**
     * Emails a 6-digit password reset code. Unknown emails are ignored by Supabase.
     *
     * @param email email address
     */
    public void sendPasswordResetCode(String email) {
        api.recover(Map.of("email", email));
        LOG.debug("Password reset code sent");
    }

    /**
     * Verifies a password reset code. The returned session is used to set the new password.
     *
     * @param email email address the code was sent to
     * @param code  6-digit code
     * @return session
     */
    public AuthSession verifyPasswordResetCode(String email, String code) {
        return api.verify(Map.of("email", email, "token", code, "type", "recovery"));
    }

    /**
     * Sets a new password for the signed-in user.
     *
     * @param accessToken user's access token
     * @param password    new password
     */
    public void updatePassword(String accessToken, String password) {
        api.updateUser("Bearer " + accessToken, Map.of("password", password));
        LOG.info("Password changed");
    }

    /**
     * Verifies an emailed code.
     *
     * @param email email address the code was sent to
     * @param code  6-digit code
     * @return session
     */
    public AuthSession verifySignInCode(String email, String code) {
        return api.verify(Map.of("email", email, "token", code, "type", "email"));
    }

    /**
     * Exchanges a refresh token for a new session without blocking.
     *
     * @param refreshToken current refresh token
     * @return new session
     */
    public Uni<AuthSession> refresh(String refreshToken) {
        return api.tokenAsync("refresh_token", Map.of("refresh_token", refreshToken));
    }

    /**
     * Revokes the user's session. Failures are logged, not thrown: sign-out must
     * always clear local cookies.
     *
     * @param accessToken current access token
     */
    public void signOut(String accessToken) {
        try {
            api.logout("Bearer " + accessToken);
        } catch (RuntimeException e) {
            LOG.warnf("Supabase logout failed: %s", e.getMessage());
        }
    }

    /**
     * Plain-language message for a sign-in/sign-up failure.
     *
     * @param e failure from Supabase
     * @return message safe to show users
     */
    public static String userMessage(SupabaseException e) {
        String code = e.errorCode() == null ? "" : e.errorCode();
        return switch (code) {
            case "invalid_credentials" -> "That email and password don't match. Try again, or reset your password.";
            case "otp_expired" -> "That code is wrong or has expired. Check the email or send a new code.";
            case "user_already_exists", "email_exists" -> "There's already an account with this email. Sign in instead.";
            case "same_password" -> "Your new password must be different from your old one.";
            case "weak_password" -> "Please choose a password with at least 8 characters.";
            case "email_address_invalid", "validation_failed" -> "Please check the email address.";
            case "over_email_send_rate_limit", "over_request_rate_limit" -> "Too many attempts. Please wait a minute and try again.";
            default -> "Something went wrong. Please try again.";
        };
    }
}
