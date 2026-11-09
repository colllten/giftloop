package com.giftloop.profile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Validates uploaded avatar images and normalizes them to a 256×256 JPEG.
 * Re-encoding drops all metadata (EXIF, GPS location).
 */
public final class AvatarImages {

    /** Largest accepted upload. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Output edge length in pixels. */
    public static final int SIZE = 256;
    private static final float JPEG_QUALITY = 0.85f;

    private AvatarImages() {
    }

    /**
     * Rejected upload, with a message safe to show users.
     */
    public static class InvalidAvatarException extends Exception {
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
     * @throws InvalidAvatarException if too large, not JPEG/PNG, or unreadable
     */
    public static byte[] normalize(byte[] upload) throws InvalidAvatarException {
        if (upload == null || upload.length == 0) {
            throw new InvalidAvatarException("Please choose a photo.");
        }
        if (upload.length > MAX_BYTES) {
            throw new InvalidAvatarException("That photo is too large. Please choose one under 2 MB.");
        }
        if (!isJpeg(upload) && !isPng(upload)) {
            throw new InvalidAvatarException("Please choose a JPEG or PNG photo.");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(upload));
        } catch (IOException e) {
            source = null;
        }
        if (source == null) {
            throw new InvalidAvatarException("We couldn't read that photo. Please try a different one.");
        }
        return toJpeg(cropAndScale(source));
    }

    private static BufferedImage cropAndScale(BufferedImage source) {
        int edge = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - edge) / 2;
        int y = (source.getHeight() - edge) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE); // background for transparent PNGs
            g.fillRect(0, 0, SIZE, SIZE);
            g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + edge, y + edge, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] toJpeg(BufferedImage image) throws InvalidAvatarException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException e) {
            throw new InvalidAvatarException("We couldn't process that photo. Please try a different one.");
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }
}
