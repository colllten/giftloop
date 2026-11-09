package com.giftloop.profile;

import com.giftloop.supabase.SupabaseException;
import com.giftloop.supabase.SupabaseStorageApi;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Stores avatars in the private {@code avatars} bucket as the calling user, so
 * Storage RLS decides who can read and write (owner writes, co-members read).
 */
@ApplicationScoped
public class AvatarService {

    private static final Logger LOG = Logger.getLogger(AvatarService.class);

    private final SupabaseStorageApi storage;
    private final ProfileService profiles;

    /**
     * Creates the service.
     *
     * @param storage  Supabase Storage client
     * @param profiles profile access
     */
    public AvatarService(@RestClient SupabaseStorageApi storage, ProfileService profiles) {
        this.storage = storage;
        this.profiles = profiles;
    }

    /**
     * Stores an already-normalized avatar, points the profile at it, then deletes the old one.
     *
     * @param jwt     caller's verified token
     * @param current caller's current profile
     * @param jpeg    output of {@link AvatarImages#normalize}
     */
    public void replace(JsonWebToken jwt, Profile current, byte[] jpeg) {
        String owner = current.id().toString();
        String file = "avatar-" + System.currentTimeMillis() + ".jpg";
        storage.upload(bearer(jwt), owner, file, jpeg);
        profiles.setAvatarPath(jwt, owner + "/" + file);
        LOG.infof("Avatar updated for %s", owner);
        deleteQuietly(jwt, current.avatarPath());
    }

    /**
     * Removes the caller's avatar.
     *
     * @param jwt     caller's verified token
     * @param current caller's current profile
     */
    public void remove(JsonWebToken jwt, Profile current) {
        profiles.setAvatarPath(jwt, null);
        deleteQuietly(jwt, current.avatarPath());
    }

    /**
     * Loads a user's avatar if the caller may see it.
     *
     * @param jwt    caller's verified token
     * @param userId whose avatar
     * @return JPEG bytes, or empty if none or not permitted
     */
    public Optional<byte[]> load(JsonWebToken jwt, UUID userId) {
        Optional<String> path = profiles.findVisible(jwt, userId).map(Profile::avatarPath);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = path.get().split("/", 2);
        try {
            return Optional.of(storage.download(bearer(jwt), parts[0], parts[1]));
        } catch (SupabaseException e) {
            LOG.debugf("Avatar not readable for %s: %s", userId, e.getMessage());
            return Optional.empty();
        }
    }

    private void deleteQuietly(JsonWebToken jwt, String path) {
        if (path == null) {
            return;
        }
        String[] parts = path.split("/", 2);
        try {
            storage.delete(bearer(jwt), parts[0], parts[1]);
        } catch (RuntimeException e) {
            LOG.warnf("Could not delete old avatar %s: %s", path, e.getMessage());
        }
    }

    private static String bearer(JsonWebToken jwt) {
        return "Bearer " + jwt.getRawToken();
    }
}
