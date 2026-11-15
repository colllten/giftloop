package com.giftloop.wishlist;

import com.giftloop.image.Images;
import com.giftloop.security.RlsContext;
import com.giftloop.supabase.SupabaseException;
import com.giftloop.supabase.SupabaseStorageApi;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.giftloop.security.Sql.prepare;

/**
 * Gift photos in the private {@code gift-images} bucket, stored as the calling user so
 * Storage RLS applies: the wishlist owner writes, anyone who can see the gift reads.
 *
 * <p>Objects live at {@code <owner>/<gift>/<random>.jpg}. Replaced and deleted files are
 * queued by the database ({@code GiftImageOrphan}); {@link #sweep} deletes the caller's.
 * Storage failures are logged and reported as {@code false}/empty, never thrown: a gift
 * is still worth saving without its photo.
 */
@ApplicationScoped
public class GiftImageService {

    /** Largest accepted source photo (upload or fetched). */
    public static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;
    /** Longest stored edge in pixels. */
    public static final int MAX_EDGE = 640;

    private static final Logger LOG = Logger.getLogger(GiftImageService.class);
    private static final Pattern FILE = Pattern.compile("[a-z0-9]{8,40}\\.jpg");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SupabaseStorageApi storage;
    private final RlsContext rls;

    /**
     * Creates the service.
     *
     * @param storage Supabase Storage client
     * @param rls     user-scoped DB access
     */
    public GiftImageService(@RestClient SupabaseStorageApi storage, RlsContext rls) {
        this.storage = storage;
        this.rls = rls;
    }

    /**
     * Validates and re-encodes a gift photo.
     *
     * @param source raw bytes (upload, staged data, or fetched image)
     * @return JPEG bytes, longest edge at most {@link #MAX_EDGE}
     * @throws Images.InvalidImageException if unusable
     */
    public static byte[] normalize(byte[] source) throws Images.InvalidImageException {
        return Images.fit(source, MAX_SOURCE_BYTES, MAX_EDGE);
    }

    /**
     * Stores a normalized photo for a gift the caller owns and points the gift at it.
     * The previous file (if any) is cleaned up via the orphan queue.
     *
     * @param jwt    caller's verified token
     * @param giftId gift id
     * @param jpeg   output of {@link #normalize}
     * @return whether the photo was stored
     */
    public boolean save(JsonWebToken jwt, UUID giftId, byte[] jpeg) {
        String owner = jwt.getSubject();
        String file = randomFile();
        try {
            storage.uploadGiftImage(bearer(jwt), owner, giftId.toString(), file, jpeg);
        } catch (RuntimeException e) {
            LOG.warnf("Could not upload photo for gift %s: %s", giftId, e.getMessage());
            return false;
        }
        String path = owner + "/" + giftId + "/" + file;
        if (!setPath(jwt, giftId, path)) {
            deleteQuietly(jwt, path);
            return false;
        }
        LOG.infof("User %s set a photo on gift %s", owner, giftId);
        sweep(jwt);
        return true;
    }

    /**
     * Copies another gift's photo (same owner) onto a gift the caller owns, server side.
     *
     * @param jwt        caller's verified token
     * @param sourcePath existing {@code image_path} the caller can read
     * @param giftId     destination gift id
     * @return whether the photo was copied
     */
    public boolean copy(JsonWebToken jwt, String sourcePath, UUID giftId) {
        String path = jwt.getSubject() + "/" + giftId + "/" + randomFile();
        try {
            storage.copy(bearer(jwt), new SupabaseStorageApi.CopyRequest("gift-images", sourcePath, path));
        } catch (RuntimeException e) {
            LOG.warnf("Could not copy photo %s to gift %s: %s", sourcePath, giftId, e.getMessage());
            return false;
        }
        if (!setPath(jwt, giftId, path)) {
            deleteQuietly(jwt, path);
            return false;
        }
        return true;
    }

    /**
     * Clears a gift's photo (owner). The file is deleted via the orphan queue.
     *
     * @param jwt    caller's verified token
     * @param giftId gift id
     */
    public void remove(JsonWebToken jwt, UUID giftId) {
        if (setPath(jwt, giftId, null)) {
            LOG.infof("User %s removed the photo of gift %s", jwt.getSubject(), giftId);
        }
        sweep(jwt);
    }

    /**
     * Loads a gift photo if Storage RLS lets the caller see the gift.
     *
     * @param jwt   caller's verified token
     * @param owner owner id (first path segment)
     * @param gift  gift id (second path segment)
     * @param file  file name
     * @return JPEG bytes, or empty if missing, malformed or not permitted
     */
    public Optional<byte[]> load(JsonWebToken jwt, UUID owner, UUID gift, String file) {
        if (file == null || !FILE.matcher(file).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(storage.downloadGiftImage(bearer(jwt), owner.toString(), gift.toString(), file));
        } catch (SupabaseException e) {
            LOG.debugf("Gift photo %s/%s/%s not readable: %s", owner, gift, file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deletes the caller's queued orphan files (replaced or deleted gift photos).
     * Failures are logged; the files stay behind.
     *
     * @param jwt caller's verified token
     */
    public void sweep(JsonWebToken jwt) {
        List<String> paths;
        try {
            paths = rls.inUserTransaction(jwt, c -> {
                List<String> out = new ArrayList<>();
                try (PreparedStatement s = c.prepareStatement("select * from private.take_gift_image_orphans()");
                     ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        out.add(r.getString(1));
                    }
                }
                return out;
            });
        } catch (RuntimeException e) {
            LOG.warnf("Could not read gift photo orphans for %s: %s", jwt.getSubject(), e.getMessage());
            return;
        }
        paths.forEach(path -> deleteQuietly(jwt, path));
    }

    /** Sets image_path as the caller; false if RLS hid the row or the write failed. */
    private boolean setPath(JsonWebToken jwt, UUID giftId, String path) {
        try {
            return rls.inUserTransaction(jwt, c -> {
                try (PreparedStatement s = prepare(c, "update \"Gift\" set image_path = ? where id = ?", path, giftId)) {
                    return s.executeUpdate() == 1;
                }
            });
        } catch (RuntimeException e) {
            LOG.warnf("Could not set photo path on gift %s: %s", giftId, e.getMessage());
            return false;
        }
    }

    private void deleteQuietly(JsonWebToken jwt, String path) {
        String[] parts = path.split("/", 3);
        if (parts.length != 3) {
            return;
        }
        try {
            storage.deleteGiftImage(bearer(jwt), parts[0], parts[1], parts[2]);
        } catch (RuntimeException e) {
            LOG.warnf("Could not delete gift photo %s: %s", path, e.getMessage());
        }
    }

    private static String randomFile() {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes) + ".jpg";
    }

    private static String bearer(JsonWebToken jwt) {
        return "Bearer " + jwt.getRawToken();
    }
}
