package org.example.canvasproject.service;

import org.example.canvasproject.models.User;
import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Аутентификация.
 *
 * Основной режим — идентичность устройства: клиент присылает случайный ключ,
 * сервер хранит только его SHA-256. Регистрация и пароль не нужны.
 * Вход по логину/паролю оставлен для совместимости со старыми клиентами.
 */
public class AuthService {

    private static AuthService instance;
    private final MongoDBService mongo;

    private AuthService() {
        this.mongo = MongoDBService.getInstance();
    }

    public static synchronized AuthService getInstance() {
        if (instance == null) instance = new AuthService();
        return instance;
    }

    /** Возвращает профиль устройства. Если создание запрещено — null для неизвестного ключа. */
    public User loginWithDeviceKey(String deviceKey, String deviceName) {
        return loginWithDeviceKey(deviceKey, deviceName, true);
    }

    public User loginWithDeviceKey(String deviceKey, String deviceName, boolean allowCreate) {
        String key = deviceKey == null ? "" : deviceKey.trim();
        if (!isValidDeviceKey(key)) return null;
        String hash = sha256(key);

        User user = mongo.findUserByDeviceKey(hash);
        if (user != null) return user;
        if (!allowCreate) return null;

        String name = sanitizeName(deviceName);
        try {
            return mongo.createDeviceUser(hash, name);
        } catch (Exception e) {
            // Параллельный запрос с тем же ключом мог создать профиль первым.
            return mongo.findUserByDeviceKey(hash);
        }
    }

    /** Ключ устройства — Base64URL, 32+ байта энтропии. */
    private boolean isValidDeviceKey(String key) {
        if (key.length() < 32 || key.length() > 128) return false;
        return key.matches("[A-Za-z0-9_+/=.-]+");
    }

    /** Старый режим регистрации (совместимость). */
    public User register(String username, String email, String password) {
        if (username == null || username.isBlank()) return null;
        if (password == null || password.length() < 6) return null;
        if (mongo.findUserByUsername(username) != null) return null;

        String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
        try {
            return mongo.createUser(username, email, hash);
        } catch (Exception e) {
            return null;
        }
    }

    public String issueToken(User user) {
        String token = UUID.randomUUID().toString();
        mongo.saveToken(token, user.getId().toHexString());
        return token;
    }

    /** Старый режим: логин/пароль. */
    public String login(String username, String password) {
        User user = mongo.findUserByUsername(username);
        if (user == null || user.getPasswordHash() == null) return null;

        BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), user.getPasswordHash());
        if (!result.verified) return null;

        return issueToken(user);
    }

    public User getUserByToken(String token) {
        String userId = mongo.findUserIdByToken(token);
        if (userId == null) return null;
        return mongo.findUserById(userId);
    }

    public void logout(String token) {
        mongo.deleteToken(token);
    }

    private String sanitizeName(String deviceName) {
        String name = (deviceName == null) ? "" : deviceName.trim();
        if (name.isEmpty()) name = "Моё устройство";
        if (name.length() > 40) name = name.substring(0, 40);
        return name;
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
