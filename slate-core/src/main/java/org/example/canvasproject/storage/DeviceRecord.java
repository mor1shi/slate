package org.example.canvasproject.storage;

/**
 * Устройство/пользователь. deviceKeyHash — SHA-256 ключа устройства,
 * passwordHash остаётся для совместимости со старыми аккаунтами.
 */
public record DeviceRecord(String id, String username, String email, String passwordHash,
                           String deviceKeyHash, String color, String avatar,
                           long createdAt) {

    public DeviceRecord withId(String newId) {
        return new DeviceRecord(newId, username, email, passwordHash, deviceKeyHash,
                color, avatar, createdAt);
    }
}
