package org.example.canvasproject.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Устройства в SQLite. device_key_hash уникален. */
final class SqliteDeviceRepository implements DeviceRepository {

    private final SqliteDb db;

    SqliteDeviceRepository(SqliteDb db) {
        this.db = db;
    }

    @Override
    public DeviceRecord create(DeviceRecord draft) {
        if (draft == null) throw new IllegalArgumentException("Нет устройства");
        String id = draft.id() == null || draft.id().isBlank()
                ? UUID.randomUUID().toString() : draft.id();
        long created = draft.createdAt() == 0 ? System.currentTimeMillis() : draft.createdAt();
        String username = draft.username() == null || draft.username().isBlank()
                ? "Устройство" : draft.username().trim();
        try {
            return db.write(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO devices(id, username, email, password_hash,
                                            device_key_hash, color, avatar, created_at)
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setString(1, id);
                    ps.setString(2, username);
                    ps.setString(3, draft.email());
                    ps.setString(4, draft.passwordHash());
                    ps.setString(5, draft.deviceKeyHash());
                    ps.setString(6, draft.color());
                    ps.setString(7, draft.avatar());
                    ps.setLong(8, created);
                    ps.executeUpdate();
                }
                return draft.withId(id);
            });
        } catch (StorageException e) {
            if (e.getCause() instanceof SQLException sql
                    && sql.getMessage() != null
                    && sql.getMessage().toLowerCase().contains("unique")) {
                throw new StorageException("Ключ устройства уже зарегистрирован", e);
            }
            throw e;
        }
    }

    @Override
    public Optional<DeviceRecord> find(String id) {
        if (id == null) return Optional.empty();
        return db.read(conn -> queryOne(conn, "SELECT * FROM devices WHERE id = ?", id));
    }

    @Override
    public Optional<DeviceRecord> findByDeviceKeyHash(String deviceKeyHash) {
        if (deviceKeyHash == null) return Optional.empty();
        return db.read(conn -> queryOne(conn,
                "SELECT * FROM devices WHERE device_key_hash = ?", deviceKeyHash));
    }

    @Override
    public Optional<DeviceRecord> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return db.read(conn -> queryOne(conn,
                "SELECT * FROM devices WHERE username = ? ORDER BY created_at LIMIT 1", username));
    }

    @Override
    public boolean updateProfile(String id, String username, String color, String avatar) {
        if (id == null) return false;
        String cleanName = username == null ? "" : username.trim();
        if (cleanName.isEmpty()) return false;
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE devices SET username = ?, color = ?, avatar = ? WHERE id = ?
                    """)) {
                ps.setString(1, cleanName);
                ps.setString(2, color);
                ps.setString(3, avatar);
                ps.setString(4, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean delete(String id) {
        if (id == null) return false;
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM devices WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static Optional<DeviceRecord> queryOne(java.sql.Connection conn, String sql, String arg)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static DeviceRecord read(ResultSet rs) throws SQLException {
        return new DeviceRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("device_key_hash"),
                rs.getString("color"),
                rs.getString("avatar"),
                rs.getLong("created_at"));
    }
}
