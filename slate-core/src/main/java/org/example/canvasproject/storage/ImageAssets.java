package org.example.canvasproject.storage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Подготовка изображений к хранению: проверка формата, ограничение размера
 * стороны и перекодирование. Всё делается до записи в AssetStore.
 */
public final class ImageAssets {

    public static final int DEFAULT_MAX_DIMENSION = 2048;
    public static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;

    public record Prepared(String mimeType, byte[] bytes) {}

    public static Prepared prepare(byte[] raw) {
        return prepare(raw, DEFAULT_MAX_DIMENSION, DEFAULT_MAX_BYTES);
    }

    public static Prepared prepare(byte[] raw, int maxDimension, long maxBytes) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("Пустое изображение");
        }
        String format = detectFormat(raw);
        if (format == null) {
            throw new IllegalArgumentException("Поддерживаются только PNG, JPEG и GIF");
        }
        BufferedImage source;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("Не удалось прочитать изображение");
            ImageReader reader = readers.next();
            reader.setInput(in, true, true);
            source = reader.read(0);
            reader.dispose();
        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось прочитать изображение", e);
        }

        boolean alpha = source.getColorModel().hasAlpha();
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(width, height));
        BufferedImage target = source;
        if (scale < 1.0) {
            int w = Math.max(1, (int) Math.round(width * scale));
            int h = Math.max(1, (int) Math.round(height * scale));
            target = new BufferedImage(w, h, alpha
                    ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, w, h, null);
            g.dispose();
        }

        String mime = "image/jpeg";
        String outFormat = "jpg";
        if (alpha) {
            mime = "image/png";
            outFormat = "png";
        }
        byte[] encoded = encode(target, outFormat);
        if (encoded.length > maxBytes) {
            throw new IllegalArgumentException("Изображение больше лимита после сжатия: "
                    + encoded.length);
        }
        return new Prepared(mime, encoded);
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalArgumentException("Формат " + format + " не поддерживается");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось закодировать изображение", e);
        }
    }

    private static String detectFormat(byte[] raw) {
        if (raw.length >= 8 && (raw[0] & 0xFF) == 0x89 && raw[1] == 'P' && raw[2] == 'N'
                && raw[3] == 'G') return "png";
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8) return "jpg";
        if (raw.length >= 6 && raw[0] == 'G' && raw[1] == 'I' && raw[2] == 'F') return "gif";
        return null;
    }

    public static String mimeOf(String format) {
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
    }

    private ImageAssets() {}
}
