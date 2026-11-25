package org.example.canvasproject.server;

/**
 * Настройки сервера из переменных окружения.
 *
 * WS_HOST                     адрес прослушивания (по умолчанию 127.0.0.1 — только этот компьютер)
 * WS_PORT                     порт (8765)
 * ALLOW_NEW_DEVICES           true — любой новый ключ создаёт профиль; false — только известные
 * ALLOW_PASSWORD_AUTH         устаревший вход по паролю (по умолчанию false)
 * MAX_CONNECTIONS_PER_IP      одновременных соединений с одного IP (10)
 * MAX_MESSAGES_PER_10S        сообщений с одного соединения за 10 секунд (600)
 * AUTH_TIMEOUT_SECONDS        сколько ждать AUTH_DEVICE после подключения (20)
 * TLS_KEYSTORE                путь к PKCS12 (.p12) для wss://
 * TLS_KEYSTORE_PASSWORD       пароль хранилища
 */
public final class ServerConfig {

    public static final String host() {
        return env("WS_HOST", "127.0.0.1");
    }

    public static final int port() {
        return intEnv("WS_PORT", 8765);
    }

    public static boolean allowNewDevices() {
        return boolEnv("ALLOW_NEW_DEVICES", true);
    }

    public static boolean allowPasswordAuth() {
        return boolEnv("ALLOW_PASSWORD_AUTH", false);
    }

    public static int maxConnectionsPerIp() {
        return intEnv("MAX_CONNECTIONS_PER_IP", 10);
    }

    public static int maxMessagesPer10s() {
        return intEnv("MAX_MESSAGES_PER_10S", 600);
    }

    /** Каталог картинок-ассетов. Один и тот же файл хранится один раз по SHA-256. */
    public static java.nio.file.Path assetsDir() {
        String dir = System.getenv("SLATE_ASSETS_DIR");
        if (dir != null && !dir.isBlank()) return java.nio.file.Paths.get(dir);
        return java.nio.file.Paths.get(System.getProperty("user.home"),
                ".local", "share", "canvasproject", "assets");
    }

    public static int authTimeoutSeconds() {
        return intEnv("AUTH_TIMEOUT_SECONDS", 20);
    }

    public static String tlsKeystore() {
        return System.getenv("TLS_KEYSTORE");
    }

    public static String tlsPassword() {
        return System.getenv().getOrDefault("TLS_KEYSTORE_PASSWORD", "");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int intEnv(String key, int def) {
        try {
            return Integer.parseInt(env(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolEnv(String key, boolean def) {
        String v = env(key, String.valueOf(def));
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    private ServerConfig() {}
}
