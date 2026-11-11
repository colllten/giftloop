package com.giftloop.auth;

import com.giftloop.security.SessionCookies;
import com.giftloop.supabase.AuthSession;
import com.giftloop.web.Redirects;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Input validation, messages and the signed-in response shared by the sign-in,
 * sign-up and password reset resources.
 */
final class AuthForms {

    /** Shown when an email is missing or implausible. */
    static final String INVALID_EMAIL = "Please enter a valid email address.";
    /** Shown when a password is missing on sign-in. */
    static final String MISSING_PASSWORD = "Please enter your password.";
    /** Shown when a new password is too short. Matches Supabase {@code minimum_password_length}. */
    static final String SHORT_PASSWORD = "Please choose a password with at least 8 characters.";
    /** Shown when a code isn't 6 digits. */
    static final String INVALID_CODE = "Please enter the 6-digit code from the email.";

    private static final Logger LOG = Logger.getLogger(AuthForms.class);
    // Linear time (CodeQL polynomial-ReDoS fix): the first domain label excludes '.', so the
    // dot has one possible position, and possessive quantifiers never backtrack. No repeated
    // group, which Java matches recursively (stack overflow on long input).
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]++@[^@\\s.]++\\.[^@\\s]++$");
    private static final Pattern CODE = Pattern.compile("^\\d{6}$");
    private static final int EMAIL_MAX = 254;
    private static final int PASSWORD_MIN = 8;

    private AuthForms() {
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

    /**
     * Strips whitespace from an emailed code; returns null unless it is 6 digits.
     *
     * @param code raw input
     * @return 6-digit code or null
     */
    static String normalizeCode(String code) {
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        return CODE.matcher(digits).matches() ? digits : null;
    }

    /**
     * Whether a new password meets the minimum length.
     *
     * @param password raw input
     * @return true if acceptable
     */
    static boolean isValidNewPassword(String password) {
        return password != null && password.length() >= PASSWORD_MIN;
    }

    /**
     * Redirects to {@code next} with session cookies set.
     *
     * @param cookies session cookie helper
     * @param session new session
     * @param next    safe redirect target
     * @param headers request headers
     * @return redirect response
     */
    static Response signedIn(SessionCookies cookies, AuthSession session, String next, HttpHeaders headers) {
        LOG.debug("Signed in");
        return Redirects.to(headers, next).cookie(cookies.issue(session)).build();
    }
}
