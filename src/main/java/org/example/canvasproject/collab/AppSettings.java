package org.example.canvasproject.collab;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Локальные настройки клиента: адрес сервера.
 * Файл ~/.config/canvasproject/settings.json (права 600).
 */
public final class AppSettings {

    private static final Path DIR = configDir();
    private static final Path FILE = DIR.resolve("settings.json");

    private static Path configDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg, "canvasproject");
        }
        return Paths.get(System.getProperty("user.home"), ".config", "canvasproject");
    }

    private static String cachedUrl;

    public static String serverUrl() {
        if (cachedUrl != null) return cachedUrl;

        // Явно сохранённый в интерфейсе адрес важнее переменной окружения:
        // иначе кнопка «Сервер…» не работала бы при WS_URL из .env.
        String saved = load();
        if (saved != null) {
            cachedUrl = saved;
            return cachedUrl;
        }

        String env = System.getenv("WS_URL");
        cachedUrl = (env == null || env.isBlank()) ? "ws://localhost:8765" : env.trim();
        return cachedUrl;
    }

    public static void setServerUrl(String url) {
        cachedUrl = url;
        try {
            Files.createDirectories(DIR);
            JSONObject json = new JSONObject();
            json.put("serverUrl", url);
            Files.writeString(FILE, json.toString(2), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(FILE,
                        java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (Exception ignored) {
                // Windows и ФС без POSIX-прав.
            }
        } catch (Exception ignored) {
        }
    }

    private static String load() {
        try {
            if (!Files.exists(FILE)) return null;
            JSONObject json = new JSONObject(Files.readString(FILE, StandardCharsets.UTF_8));
            String url = json.optString("serverUrl", "").trim();
            return url.isEmpty() ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    private AppSettings() {}
}
