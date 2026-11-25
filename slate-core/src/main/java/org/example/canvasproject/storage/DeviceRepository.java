package org.example.canvasproject.storage;

import java.util.Optional;

/** Устройства и старые аккаунты. Ключ устройства уникален. */
public interface DeviceRepository {

    DeviceRecord create(DeviceRecord draft);

    Optional<DeviceRecord> find(String id);

    Optional<DeviceRecord> findByDeviceKeyHash(String deviceKeyHash);

    Optional<DeviceRecord> findByUsername(String username);

    boolean updateProfile(String id, String username, String color, String avatar);

    boolean delete(String id);
}
