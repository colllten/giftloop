package com.giftloop.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Image validation and re-encoding shared by avatars and gift photos.
 */
class ImagesTest {

    private static final int MB = 1024 * 1024;

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] jpeg) throws Exception {
        assertEquals((byte) 0xFF, jpeg[0], "JPEG output");
        assertEquals((byte) 0xD8, jpeg[1], "JPEG output");
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    /** A wide image keeps its aspect ratio and fits the longest edge. */
    @Test
    void fitScalesDownKeepingAspect() throws Exception {
        BufferedImage out = read(Images.fit(png(1600, 800), 5 * MB, 640));
        assertEquals(640, out.getWidth());
        assertEquals(320, out.getHeight());
    }

    /** Small images are never upscaled. */
    @Test
    void fitNeverUpscales() throws Exception {
        BufferedImage out = read(Images.fit(png(200, 100), 5 * MB, 640));
        assertEquals(200, out.getWidth());
        assertEquals(100, out.getHeight());
    }

    /** WebP (common on retailer sites) is decoded and re-encoded as JPEG. */
    @Test
    void readsWebp() throws Exception {
        byte[] webp;
        try (InputStream in = getClass().getResourceAsStream("/images/sample.webp")) {
            webp = in.readAllBytes();
        }
        assertTrue(Images.isSupported(webp));
        BufferedImage out = read(Images.fit(webp, 5 * MB, 640));
        assertEquals(120, out.getWidth());
        assertEquals(80, out.getHeight());
        Color left = new Color(out.getRGB(10, 40));
        assertTrue(left.getRed() > 200 && left.getBlue() < 60, "kept the pixels");
    }

    /** Images over the edge limit are rejected before decoding. */
    @Test
    void rejectsHugeDimensions() throws Exception {
        byte[] huge = png(Images.MAX_SOURCE_EDGE + 1, 10);
        Images.InvalidImageException e = assertThrows(Images.InvalidImageException.class,
                () -> Images.fit(huge, 5 * MB, 640));
        assertTrue(e.getMessage().contains("too large"));
    }

    /** Square crop for avatars still produces the requested size. */
    @Test
    void squareCrops() throws Exception {
        BufferedImage out = read(Images.square(png(900, 300), 2 * MB, 256));
        assertEquals(256, out.getWidth());
        assertEquals(256, out.getHeight());
    }

    /** Empty, oversized and unsupported inputs are rejected. */
    @Test
    void rejectsBadInput() {
        assertThrows(Images.InvalidImageException.class, () -> Images.fit(null, MB, 640));
        assertThrows(Images.InvalidImageException.class, () -> Images.fit(new byte[MB + 1], MB, 640));
        assertThrows(Images.InvalidImageException.class, () -> Images.fit("GIF89a....".getBytes(), MB, 640));
    }
}
