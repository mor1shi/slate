package org.example.canvasproject.collab;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Идентичность устройства.
 *
 * При первом запуске создаётся случайный ключ (32 байта, Base64URL) и
 * сохраняется в ~/.config/canvasproject/device.json с правами 600.
 * Ключ — единственный секрет: по нему сервер находит доски пользователя.
 * Тот же ключ можно перенести на другое устройство, чтобы получить доступ
 * к тем же доскам.
 */
public final class DeviceIdentity {

    private static final Path CONFIG_DIR =
            Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME",
                    System.getProperty("user.home") + "/.config"), "canvasproject");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("device.json");

    private static DeviceIdentity instance;

    private String key;
    private String name;

    private DeviceIdentity() {
        load();
    }

    public static synchronized DeviceIdentity getInstance() {
        if (instance == null) instance = new DeviceIdentity();
        return instance;
    }

    public String getKey()  { return key; }
    public String getName() { return name; }

    public void setName(String newName) {
        if (newName == null || newName.isBlank()) return;
        this.name = newName.trim();
        save();
    }

    /** Заменяет ключ (перенос идентичности с другого устройства). */
    public boolean useKey(String rawKey) {
        String normalized = normalizeKey(rawKey);
        if (normalized == null || normalized.equals(key)) return false;
        this.key = normalized;
        save();
        return true;
    }

    public static String normalizeKey(String raw) {
        if (raw == null) return null;
        // Убираем только пробелы: дефис и подчёркивание — часть Base64URL.
        String cleaned = raw.trim().replaceAll("\\s", "");
        if (cleaned.length() < 16 || cleaned.length() > 128) return null;
        if (!cleaned.matches("[A-Za-z0-9_+/=.-]+")) return null;
        return cleaned;
    }

    private void load() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                JSONObject json = new JSONObject(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8));
                key  = normalizeKey(json.optString("key", ""));
                name = json.optString("name", "");
            }
        } catch (Exception ignored) {
            // Повреждённый файл будет пересоздан ниже.
        }
        if (key == null) {
            key = generateKey();
        }
        if (name == null || name.isBlank()) {
            name = defaultName();
        }
        save();
    }

    private void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JSONObject json = new JSONObject()
                    .put("key", key)
                    .put("name", name)
                    .put("createdAt", System.currentTimeMillis());
            Files.writeString(CONFIG_FILE, json.toString(2), StandardCharsets.UTF_8);
            restrictPermissions(CONFIG_FILE);
        } catch (Exception ignored) {
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception ignored) {
        }
    }

    private static String generateKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String defaultName() {
        String user = System.getProperty("user.name", "");
        if (user.isBlank()) return "Моё устройство";
        return Character.toUpperCase(user.charAt(0)) + user.substring(1);
    }
}
