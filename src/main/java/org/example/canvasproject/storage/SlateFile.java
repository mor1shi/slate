package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Формат обмена и резервных копий `.slate`: zip с `board.json` и
 * `assets/<sha256>`.
 *
 * <p>Импорт сначала читает и проверяет весь архив, и только потом вызывающий
 * код что-то сохраняет. Пути внутри архива ограничены `board.json` и
 * `assets/<hex>`; всё остальное — ошибка. Лимиты защищают от zip-бомб.
 */
public final class SlateFile {

    public static final String FORMAT = "slate-board";
    public static final int VERSION = 1;

    public static final String BOARD_ENTRY = "board.json";
    public static final String ASSETS_PREFIX = "assets/";

    public static final int MAX_FILES = 512;
    public static final long MAX_BOARD_BYTES = 64L * 1024 * 1024;
    public static final long MAX_ASSET_BYTES = 16L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ASSET_ENTRY = Pattern.compile("assets/([0-9a-f]{64})");

    /** Ограничения распаковки; в тестах уменьшаются, чтобы не плодить гигабайты. */
    public record Limits(int maxFiles, long maxBoardBytes, long maxAssetBytes,
                         long maxTotalBytes) {
        public static final Limits DEFAULT = new Limits(MAX_FILES, MAX_BOARD_BYTES,
                MAX_ASSET_BYTES, MAX_TOTAL_BYTES);

        public Limits {
            if (maxFiles < 1 || maxBoardBytes < 1 || maxAssetBytes < 1 || maxTotalBytes < 1) {
                throw new IllegalArgumentException("Лимиты должны быть положительными");
            }
        }
    }

    /** Проверенное содержимое архива: доска и её ассеты (SHA-256 → байты). */
    public record Contents(LocalBoard board, Map<String, byte[]> assets) {
        public Contents {
            assets = assets == null ? Map.of() : Map.copyOf(assets);
        }
    }

    /** Запись без ассетов: используется, когда хранилища файлов под рукой нет. */
    public static void write(LocalBoard board, Path target) throws IOException {
        write(board, null, target);
    }

    /**
     * Записывает доску и только те ассеты, на которые ссылаются её элементы
     * (`data.assetId`). Отсутствующие в хранилище файлы пропускаются: доска
     * остаётся открываемой, картинка запросится заново.
     */
    public static void write(LocalBoard board, AssetStore assets, Path target) throws IOException {
        if (board == null) throw new IllegalArgumentException("Нет доски");
        JSONObject json = toJson(board);
        Map<String, byte[]> used = collectAssets(board.getElements(), assets);

        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".slate-", ".tmp");
        try (OutputStream out = Files.newOutputStream(temp);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(BOARD_ENTRY));
            zip.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> asset : used.entrySet()) {
                zip.putNextEntry(new ZipEntry(ASSETS_PREFIX + asset.getKey()));
                zip.write(asset.getValue());
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        try {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Contents read(Path source) throws IOException {
        return read(source, Limits.DEFAULT);
    }

    /** Читает и проверяет архив целиком; при любой ошибке ничего не пишет. */
    public static Contents read(Path source, Limits limits) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new SlateFileException("error.slate.read", String.valueOf(source));
        }
        JSONObject json = null;
        Map<String, byte[]> assets = new LinkedHashMap<>();
        int files = 0;
        long total = 0;

        try (InputStream in = Files.newInputStream(source);
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (!ASSETS_PREFIX.equals(name) && !ASSETS_PREFIX.equals(name + "/")) {
                        throw new SlateFileException("error.slate.unsafePath", name);
                    }
                    continue;
                }
                files++;
                if (files > limits.maxFiles()) {
                    throw new SlateFileException("error.slate.tooManyFiles", limits.maxFiles());
                }
                if (BOARD_ENTRY.equals(name)) {
                    if (json != null) {
                        throw new SlateFileException("error.slate.invalidJson",
                                "дубликат " + BOARD_ENTRY);
                    }
                    byte[] bytes = readLimited(zip, limits.maxBoardBytes());
                    total = addSize(total, bytes.length, limits.maxTotalBytes());
                    json = parse(bytes);
                } else {
                    Matcher matcher = ASSET_ENTRY.matcher(name);
                    if (!matcher.matches()) {
                        throw new SlateFileException("error.slate.unsafePath", name);
                    }
                    byte[] bytes = readLimited(zip, limits.maxAssetBytes());
                    total = addSize(total, bytes.length, limits.maxTotalBytes());
                    String expected = matcher.group(1);
                    if (!expected.equals(FileAssetStore.hash(bytes))) {
                        throw new SlateFileException("error.slate.assetMismatch", name);
                    }
                    assets.put(expected, bytes);
                }
            }
        }

        if (json == null) throw new SlateFileException("error.slate.noBoard");
        return new Contents(fromJson(json), assets);
    }

    /** Сериализация доски для `board.json` и снимков. */
    public static JSONObject toJson(LocalBoard board) {
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
        return json;
    }

    /** Обратное преобразование с проверкой формата и версии. */
    public static LocalBoard fromJson(JSONObject json) throws SlateFileException {
        if (json == null) throw new SlateFileException("error.slate.invalidJson", "null");
        if (!FORMAT.equals(json.optString("format"))) {
            throw new SlateFileException("error.slate.format");
        }
        int version = json.optInt("version", 0);
        if (version != VERSION) {
            throw new SlateFileException("error.slate.version", version, VERSION);
        }
        JSONArray elements = json.optJSONArray("elements");
        if (json.has("elements") && elements == null) {
            throw new SlateFileException("error.slate.invalidJson", "elements");
        }
        return new LocalBoard(
                json.optString("id", "imported-" + System.currentTimeMillis()),
                json.optString("name", "Без названия"),
                json.optInt("width", 1920),
                json.optInt("height", 1080),
                json.optLong("createdAt", System.currentTimeMillis()),
                json.optLong("updatedAt", System.currentTimeMillis()),
                elements == null ? new JSONArray() : elements);
    }

    private static Map<String, byte[]> collectAssets(JSONArray elements, AssetStore store) {
        Map<String, byte[]> result = new TreeMap<>();
        if (elements == null || store == null) return result;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item == null) continue;
            JSONObject data = item.optJSONObject("data");
            if (data == null) continue;
            String hash = data.optString("assetId", "");
            if (!HASH.matcher(hash).matches() || result.containsKey(hash)) continue;
            store.get(hash).ifPresent(bytes -> result.put(hash, bytes));
        }
        return result;
    }

    private static JSONObject parse(byte[] bytes) throws SlateFileException {
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new SlateFileException("error.slate.invalidJson", e.getMessage());
        }
    }

    private static long addSize(long current, long added, long limit) throws SlateFileException {
        long total = current + added;
        if (total > limit) throw new SlateFileException("error.slate.tooLarge", limit);
        return total;
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new SlateFileException("error.slate.tooLarge", limit);
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private SlateFile() {}
}
