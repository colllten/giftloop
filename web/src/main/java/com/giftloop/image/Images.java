package com.giftloop.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Validates untrusted images (uploads, fetched product photos) and re-encodes them
 * as JPEG. Re-encoding drops all metadata (EXIF, GPS) and anything that isn't pixels.
 *
 * <p>Accepts JPEG, PNG and WebP (WebP via the TwelveMonkeys ImageIO plugin).
 * Dimensions are checked from the header before decoding, so a small file can't
 * expand into a huge bitmap.
 */
public final class Images {

    /** Largest accepted width or height of a source image. */
    public static final int MAX_SOURCE_EDGE = 6000;
    private static final float JPEG_QUALITY = 0.85f;

    private Images() {
    }

    /**
     * Rejected image, with a message safe to show users.
     */
    public static class InvalidImageException extends Exception {
        /**
         * Creates the exception.
         *
         * @param message user-facing reason
         */
        public InvalidImageException(String message) {
            super(message);
        }
    }

    /**
     * Center-crops to a square and scales to {@code size}×{@code size} (avatars).
     *
     * @param source   raw image bytes
     * @param maxBytes largest accepted input
     * @param size     output edge in pixels
     * @return JPEG bytes
     * @throws InvalidImageException if empty, too large, not JPEG/PNG/WebP, or unreadable
     */
    public static byte[] square(byte[] source, int maxBytes, int size) throws InvalidImageException {
        BufferedImage image = decode(source, maxBytes);
        int edge = Math.min(image.getWidth(), image.getHeight());
        int x = (image.getWidth() - edge) / 2;
        int y = (image.getHeight() - edge) / 2;
        return toJpeg(draw(image, size, size, x, y, edge, edge));
    }

    /**
     * Scales down to fit within {@code maxEdge} on the longest side, keeping the aspect
     * ratio and never upscaling (gift photos).
     *
     * @param source   raw image bytes
     * @param maxBytes largest accepted input
     * @param maxEdge  longest output edge in pixels
     * @return JPEG bytes
     * @throws InvalidImageException if empty, too large, not JPEG/PNG/WebP, or unreadable
     */
    public static byte[] fit(byte[] source, int maxBytes, int maxEdge) throws InvalidImageException {
        BufferedImage image = decode(source, maxBytes);
        double scale = Math.min(1.0, (double) maxEdge / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return toJpeg(draw(image, width, height, 0, 0, image.getWidth(), image.getHeight()));
    }

    /**
     * True if the bytes start like a JPEG, PNG or WebP file.
     *
     * @param b bytes to sniff
     * @return whether the format is supported
     */
    public static boolean isSupported(byte[] b) {
        return isJpeg(b) || isPng(b) || isWebp(b);
    }

    private static BufferedImage decode(byte[] source, int maxBytes) throws InvalidImageException {
        if (source == null || source.length == 0) {
            throw new InvalidImageException("Please choose a photo.");
        }
        if (source.length > maxBytes) {
            throw new InvalidImageException("That photo is too large. Please choose one under "
                    + (maxBytes / (1024 * 1024)) + " MB.");
        }
        if (!isSupported(source)) {
            throw new InvalidImageException("Please choose a JPEG, PNG or WebP photo.");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) {
                throw unreadable();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if (reader.getWidth(0) > MAX_SOURCE_EDGE || reader.getHeight(0) > MAX_SOURCE_EDGE) {
                    throw new InvalidImageException("That photo is too large. Please choose a smaller one.");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw unreadable();
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw unreadable();
        }
    }

    private static InvalidImageException unreadable() {
        return new InvalidImageException("We couldn't read that photo. Please try a different one.");
    }

    /** Draws a source region onto a white canvas (transparent PNGs/WebPs get a white background). */
    private static BufferedImage draw(BufferedImage source, int width, int height,
                                      int sx, int sy, int sw, int sh) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, sx, sy, sx + sw, sy + sh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] toJpeg(BufferedImage image) throws InvalidImageException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException e) {
            throw new InvalidImageException("We couldn't process that photo. Please try a different one.");
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

    private static boolean isWebp(byte[] b) {
        return b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
