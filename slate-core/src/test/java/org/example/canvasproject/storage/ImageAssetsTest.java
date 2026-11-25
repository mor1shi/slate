package org.example.canvasproject.storage;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAssetsTest {

    @Test
    void largeImageIsScaledDownAndReencoded() throws Exception {
        BufferedImage source = new BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 3000, 1500);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);

        ImageAssets.Prepared prepared = ImageAssets.prepare(out.toByteArray(), 1024, 4_000_000);
        BufferedImage stored = ImageIO.read(new java.io.ByteArrayInputStream(prepared.bytes()));
        assertEquals(1024, stored.getWidth());
        assertEquals(512, stored.getHeight());
        assertEquals("image/jpeg", prepared.mimeType());
    }

    @Test
    void nonImageIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ImageAssets.prepare("не картинка".getBytes()));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAssets.prepare(new byte[0]));
    }

    @Test
    void tooLargeResultIsRejected() throws Exception {
        BufferedImage source = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 800, 800);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        assertThrows(IllegalArgumentException.class,
                () -> ImageAssets.prepare(out.toByteArray(), 800, 100));
    }

    @Test
    void smallImageIsKeptWhenWithinLimit() throws Exception {
        BufferedImage source = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        ImageAssets.Prepared prepared = ImageAssets.prepare(out.toByteArray());
        assertEquals("image/png", prepared.mimeType());
        assertTrue(prepared.bytes().length > 0);
    }
}
