package com.giftloop.supabase;

import com.giftloop.observability.AppMetrics;
import com.giftloop.observability.AppMetrics.CodeFlow;
import com.giftloop.observability.AppMetrics.SignInMethod;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Sign-up (emailed code), sign-in (password or emailed code), password reset, refresh and sign-out
 * against Supabase Auth.
 * Failures surface as {@link SupabaseException}; {@link #userMessage} gives friendly text.
 */
@ApplicationScoped
public class SupabaseAuthService {

    private static final Logger LOG = Logger.getLogger(SupabaseAuthService.class);

    /** Supabase error when {@code /otp} is called with {@code create_user=false} for an unknown email. */
    static final String OTP_NO_ACCOUNT = "otp_disabled";

    private final SupabaseAuthApi api;
    private final AppMetrics metrics;

    /**
     * Creates the service.
     *
     * @param api     Supabase Auth REST client
     * @param metrics app metrics (auth counters)
     */
    public SupabaseAuthService(@RestClient SupabaseAuthApi api, AppMetrics metrics) {
        this.api = api;
        this.metrics = metrics;
    }

    /**
     * Emails a 6-digit sign-up code, creating an unconfirmed, passwordless account if
     * the email is new. Existing accounts get a sign-in code instead. Supabase responds
     * identically either way, so callers can't tell whether an account exists.
     * Verify with {@link #verifySignInCode}, then set the password with {@link #updatePassword}.
     *
     * @param email email address
     */
    public void sendSignUpCode(String email) {
        countedRun(() -> api.sendOtp(Map.of("email", email, "create_user", true)),
                ok -> metrics.codeSent(CodeFlow.SIGNUP, ok));
        LOG.debug("Sign-up code sent");
    }

    /**
     * Signs in with email and password.
     *
     * @param email    email address
     * @param password password
     * @return session
     */
    public AuthSession signInWithPassword(String email, String password) {
        return counted(() -> api.token("password", Map.of("email", email, "password", password)),
                ok -> metrics.signIn(SignInMethod.PASSWORD, ok));
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
            metrics.codeSent(CodeFlow.SIGNIN, true);
            LOG.debug("Sign-in code sent");
        } catch (SupabaseException e) {
            if (!OTP_NO_ACCOUNT.equals(e.errorCode())) {
                metrics.codeSent(CodeFlow.SIGNIN, false);
                throw e;
            }
            // Counted as sent: to the user (and the metric) unknown emails look the same.
            metrics.codeSent(CodeFlow.SIGNIN, true);
            LOG.debug("Sign-in code requested for unknown email; ignored");
        }
    }

    /**
     * Emails a 6-digit password reset code. Unknown emails are ignored by Supabase.
     *
     * @param email email address
     */
    public void sendPasswordResetCode(String email) {
        countedRun(() -> api.recover(Map.of("email", email)), ok -> metrics.codeSent(CodeFlow.RESET, ok));
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
        return counted(() -> api.verify(Map.of("email", email, "token", code, "type", "recovery")),
                ok -> metrics.signIn(SignInMethod.RESET, ok));
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
     * Verifies an emailed sign-in or sign-up code (both use {@code type=email}).
     *
     * @param email email address the code was sent to
     * @param code  6-digit code
     * @return session
     */
    public AuthSession verifySignInCode(String email, String code) {
        return counted(() -> api.verify(Map.of("email", email, "token", code, "type", "email")),
                ok -> metrics.signIn(SignInMethod.CODE, ok));
    }

    /**
     * Exchanges a refresh token for a new session without blocking.
     *
     * @param refreshToken current refresh token
     * @return new session
     */
    public Uni<AuthSession> refresh(String refreshToken) {
        return api.tokenAsync("refresh_token", Map.of("refresh_token", refreshToken))
                .onItemOrFailure().invoke((session, failure) -> metrics.sessionRefresh(failure == null));
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
     * Plain-language message for a sign-in/sign-up failure. Never reveals whether an
     * account exists.
     *
     * @param e failure from Supabase
     * @return message safe to show users
     */
    public static String userMessage(SupabaseException e) {
        String code = e.errorCode() == null ? "" : e.errorCode();
        return switch (code) {
            case "invalid_credentials" -> "That email and password don't match. Try again, or reset your password.";
            case "otp_expired" -> "That code is wrong or has expired. Check the email or send a new code.";
            case "same_password" -> "Your new password must be different from your old one.";
            case "weak_password" -> "Please choose a password with at least 8 characters.";
            case "email_address_invalid", "validation_failed" -> "Please check the email address.";
            case "over_email_send_rate_limit", "over_request_rate_limit" -> "Too many attempts. Please wait a minute and try again.";
            default -> "Something went wrong. Please try again.";
        };
    }

    /**
     * Runs a Supabase call and reports whether it succeeded.
     *
     * @param call   the call
     * @param record receives {@code true} on success, {@code false} if it threw
     * @param <T>    result type
     * @return the call's result
     */
    private static <T> T counted(Supplier<T> call, Consumer<Boolean> record) {
        try {
            T result = call.get();
            record.accept(true);
            return result;
        } catch (RuntimeException e) {
            record.accept(false);
            throw e;
        }
    }

    /**
     * {@link #counted} for calls without a result.
     *
     * @param call   the call
     * @param record receives the outcome
     */
    private static void countedRun(Runnable call, Consumer<Boolean> record) {
        counted(() -> {
            call.run();
            return null;
        }, record);
    }
}
