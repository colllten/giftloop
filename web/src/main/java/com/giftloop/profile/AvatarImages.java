package com.giftloop.profile;

import com.giftloop.image.Images;

/**
 * Validates uploaded avatar images and normalizes them to a 256×256 JPEG
 * (see {@link Images#square}). Re-encoding drops all metadata (EXIF, GPS location).
 */
public final class AvatarImages {

    /** Largest accepted upload. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Output edge length in pixels. */
    public static final int SIZE = 256;

    private AvatarImages() {
    }

    /**
     * Rejected upload, with a message safe to show users.
     */
    public static class InvalidAvatarException extends Images.InvalidImageException {
        /**
         * Creates the exception.
         *
         * @param message user-facing reason
         */
        public InvalidAvatarException(String message) {
            super(message);
        }
    }

    /**
     * Checks size and type, center-crops to a square and scales to {@link #SIZE}.
     *
     * @param upload raw uploaded bytes
     * @return JPEG bytes
     * @throws InvalidAvatarException if too large, not JPEG/PNG/WebP, or unreadable
     */
    public static byte[] normalize(byte[] upload) throws InvalidAvatarException {
        try {
            return Images.square(upload, MAX_BYTES, SIZE);
        } catch (Images.InvalidImageException e) {
            throw new InvalidAvatarException(e.getMessage());
        }
    }
}
