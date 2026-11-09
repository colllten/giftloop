package com.giftloop.profile;

import com.giftloop.security.DataAccessException;
import com.giftloop.security.RlsContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and updates profiles as the calling user (RLS applies via {@link RlsContext}).
 */
@ApplicationScoped
public class ProfileService {

    private static final Logger LOG = Logger.getLogger(ProfileService.class);
    private static final String UNIQUE_VIOLATION = "23505";
    static final String USERNAME_TAKEN = "That username is taken. Please try another.";

    private static final String SELECT = """
            select id, username, display_name, birth_month, birth_day, avatar_path, onboarded_at is not null as onboarded
            from "Profile" where id = ?""";

    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param rls user-scoped DB access
     */
    public ProfileService(RlsContext rls) {
        this.rls = rls;
    }

    /**
     * The caller's own profile.
     *
     * @param jwt caller's verified token
     * @return profile, or empty if none exists
     */
    public Optional<Profile> findOwn(JsonWebToken jwt) {
        return findVisible(jwt, UUID.fromString(jwt.getSubject()));
    }

    /**
     * A profile the caller is allowed to see (self or a co-member).
     *
     * @param jwt    caller's verified token
     * @param userId profile to read
     * @return profile, or empty if missing or not visible
     */
    public Optional<Profile> findVisible(JsonWebToken jwt, UUID userId) {
        return rls.inUserTransaction(jwt, c -> Optional.ofNullable(select(c, userId)));
    }

    /**
     * Whether a username is free (case-insensitive). The caller's own counts as free.
     *
     * @param jwt      caller's verified token
     * @param username candidate username (format already validated)
     * @return true if available
     */
    public boolean isUsernameAvailable(JsonWebToken jwt, String username) {
        return rls.inUserTransaction(jwt, c -> {
            try (PreparedStatement s = c.prepareStatement("select private.is_username_available(?)")) {
                s.setString(1, username);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    return r.getBoolean(1);
                }
            }
        });
    }

    /**
     * Validates and saves the caller's profile fields.
     *
     * @param jwt              caller's verified token
     * @param form             submitted fields
     * @param completeOnboarding true to mark onboarding complete (kept if already set)
     * @return field errors; empty on success
     */
    public Map<String, String> save(JsonWebToken jwt, ProfileForm form, boolean completeOnboarding) {
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return errors;
        }
        if (!isUsernameAvailable(jwt, form.username())) {
            return Map.of("username", USERNAME_TAKEN);
        }
        try {
            rls.inUserTransaction(jwt, c -> {
                try (PreparedStatement s = c.prepareStatement("""
                        update "Profile"
                        set username = ?, display_name = ?, birth_month = ?, birth_day = ?,
                            onboarded_at = case when ? then coalesce(onboarded_at, now()) else onboarded_at end
                        where id = auth.uid()""")) {
                    s.setString(1, form.username());
                    s.setString(2, form.displayName());
                    setNullableInt(s, 3, form.birthMonth());
                    setNullableInt(s, 4, form.birthDay());
                    s.setBoolean(5, completeOnboarding);
                    return s.executeUpdate();
                }
            });
        } catch (DataAccessException e) {
            if (e.getCause() instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return Map.of("username", USERNAME_TAKEN);
            }
            throw e;
        }
        LOG.infof("Profile saved for %s (onboarding=%s)", jwt.getSubject(), completeOnboarding);
        return Map.of();
    }

    /**
     * Sets or clears the caller's avatar path.
     *
     * @param jwt  caller's verified token
     * @param path {@code <id>/<file>}, or null to clear
     */
    public void setAvatarPath(JsonWebToken jwt, String path) {
        rls.inUserTransaction(jwt, c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "update \"Profile\" set avatar_path = ? where id = auth.uid()")) {
                s.setString(1, path);
                return s.executeUpdate();
            }
        });
    }

    private static Profile select(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(SELECT)) {
            s.setObject(1, userId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                return new Profile(r.getObject("id", UUID.class), r.getString("username"),
                        r.getString("display_name"), r.getObject("birth_month", Integer.class),
                        r.getObject("birth_day", Integer.class), r.getString("avatar_path"),
                        r.getBoolean("onboarded"));
            }
        }
    }

    private static void setNullableInt(PreparedStatement s, int index, Integer value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.SMALLINT);
        } else {
            s.setInt(index, value);
        }
    }
}
