package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Формат обмена и резервных копий `.slate`: zip с `board.json`.
 * Версия формата обязательна, незнакомые версии отвергаются.
 */
public final class SlateFile {

    public static final String FORMAT = "slate-board";
    public static final int VERSION = 1;

    private static final long MAX_BOARD_JSON = 64L * 1024 * 1024;

    public static void write(LocalBoard board, Path target) throws IOException {
        if (board == null) throw new IllegalArgumentException("Нет доски");
        JSONObject json = new JSONObject();
        json.put("format", FORMAT);
        json.put("version", VERSION);
        json.put("id", board.getId());
        json.put("name", board.getName());
        json.put("width", board.getWidth());
        json.put("height", board.getHeight());
        json.put("createdAt", board.getCreatedAt());
        json.put("updatedAt", board.getUpdatedAt());
        json.put("elements", board.getElements());

        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".slate-", ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp),
                StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("board.json"));
            zip.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/"));
            zip.closeEntry();
        }
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    public static LocalBoard read(Path source) throws IOException {
        JSONObject json = null;
        try (InputStream in = Files.newInputStream(source);
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"board.json".equals(entry.getName())) continue;
                json = new JSONObject(new String(readLimited(zip, MAX_BOARD_JSON),
                        StandardCharsets.UTF_8));
                break;
            }
        }
        if (json == null) throw new IOException("В архиве нет board.json");
        if (!FORMAT.equals(json.optString("format"))) {
            throw new IOException("Это не файл доски Slate");
        }
        int version = json.optInt("version", 0);
        if (version > VERSION || version < 1) {
            throw new IOException("Неподдерживаемая версия формата: " + version);
        }
        String name = json.optString("name", "Без названия");
        return new LocalBoard(
                json.optString("id", "imported-" + System.currentTimeMillis()),
                name,
                json.optInt("width", 1920),
                json.optInt("height", 1080),
                json.optLong("createdAt", System.currentTimeMillis()),
                json.optLong("updatedAt", System.currentTimeMillis()),
                json.optJSONArray("elements") == null ? new JSONArray() : json.getJSONArray("elements"));
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("board.json слишком большой");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private SlateFile() {}
}
