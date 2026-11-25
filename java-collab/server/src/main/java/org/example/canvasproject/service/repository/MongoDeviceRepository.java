package org.example.canvasproject.service.repository;

import org.bson.types.ObjectId;
import org.example.canvasproject.models.User;
import org.example.canvasproject.service.MongoDBService;
import org.example.canvasproject.storage.DeviceRecord;
import org.example.canvasproject.storage.DeviceRepository;
import org.example.canvasproject.storage.StorageException;

import java.util.Optional;

/** Mongo-адаптер DeviceRepository: пользователи и ключи устройств. */
final class MongoDeviceRepository implements DeviceRepository {

    private final MongoDBService mongo;

    MongoDeviceRepository(MongoDBService mongo) {
        this.mongo = mongo;
    }

    @Override
    public DeviceRecord create(DeviceRecord draft) {
        if (draft == null) throw new IllegalArgumentException("Нет устройства");
        try {
            User user = mongo.insertUserRecord(draft.username(), draft.email(),
                    draft.passwordHash(), draft.deviceKeyHash(), draft.color(),
                    draft.avatar());
            return toRecord(user);
        } catch (RuntimeException e) {
            throw new StorageException("Не удалось создать устройство", e);
        }
    }

    @Override
    public Optional<DeviceRecord> find(String id) {
        if (id == null) return Optional.empty();
        try {
            return Optional.ofNullable(mongo.findUserById(id)).map(MongoDeviceRepository::toRecord);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<DeviceRecord> findByDeviceKeyHash(String deviceKeyHash) {
        return Optional.ofNullable(mongo.findUserByDeviceKey(deviceKeyHash))
                .map(MongoDeviceRepository::toRecord);
    }

    @Override
    public Optional<DeviceRecord> findByUsername(String username) {
        return Optional.ofNullable(mongo.findUserByUsername(username))
                .map(MongoDeviceRepository::toRecord);
    }

    @Override
    public boolean updateProfile(String id, String username, String color, String avatar) {
        return mongo.updateUserProfile(id, username, color, avatar);
    }

    @Override
    public boolean delete(String id) {
        if (id == null) return false;
        try {
            new ObjectId(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return mongo.deleteUser(id);
    }

    private static DeviceRecord toRecord(User user) {
        if (user == null) return null;
        return new DeviceRecord(
                user.getId() == null ? null : user.getId().toHexString(),
                user.getUsername(), user.getEmail(), user.getPasswordHash(),
                user.getDeviceKeyHash(), user.getColor(), user.getAvatar(),
                user.getCreatedAt());
    }
}
