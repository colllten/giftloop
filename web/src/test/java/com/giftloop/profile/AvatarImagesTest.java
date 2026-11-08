package com.giftloop.profile;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Avatar validation and normalization.
 */
class AvatarImagesTest {

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** A wide PNG is center-cropped and scaled to a 256×256 JPEG. */
    @Test
    void normalizesToSquareJpeg() throws Exception {
        BufferedImage wide = new BufferedImage(900, 300, BufferedImage.TYPE_INT_RGB);
        for (int x = 300; x < 600; x++) {
            for (int y = 0; y < 300; y++) {
                wide.setRGB(x, y, Color.RED.getRGB());
            }
        }
        byte[] out = AvatarImages.normalize(encode(wide, "png"));
        assertEquals((byte) 0xFF, out[0]);
        assertEquals((byte) 0xD8, out[1]);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(256, result.getWidth());
        assertEquals(256, result.getHeight());
        Color center = new Color(result.getRGB(128, 128));
        assertEquals(true, center.getRed() > 200 && center.getGreen() < 60, "kept the center (red) region");
    }

    /** Empty, oversized, non-image and corrupt uploads are rejected. */
    @Test
    void rejectsBadUploads() {
        assertThrows(AvatarImages.InvalidAvatarException.class, () -> AvatarImages.normalize(new byte[0]));
        assertThrows(AvatarImages.InvalidAvatarException.class, () -> AvatarImages.normalize("GIF89a....".getBytes()));
        byte[] huge = new byte[AvatarImages.MAX_BYTES + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;
        assertThrows(AvatarImages.InvalidAvatarException.class, () -> AvatarImages.normalize(huge));
        byte[] corrupt = Arrays.copyOf(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 64);
        assertThrows(AvatarImages.InvalidAvatarException.class, () -> AvatarImages.normalize(corrupt));
    }
}
