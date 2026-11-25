package org.example.canvasproject.migration;

import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.DeviceRecord;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.InviteRecord;
import org.example.canvasproject.storage.SqliteStorage;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQLite-хранилище переноса. Схему создаёт и обновляет {@link SqliteStorage},
 * а данные пишутся напрямую: репозитории не умеют сохранять исходные id,
 * время создания и счётчик использований приглашения.
 *
 * <p>Запись идёт одной транзакцией с upsert по первичным ключам. Элементы и
 * участники доски зеркалятся: сначала удаляются, затем вставляются из снимка.
 */
public final class SqliteMigrationStore implements MigrationStore {

    private final Path databaseFile;
    private final Object lock = new Object();
    private Connection connection;

    public SqliteMigrationStore(Path databaseFile) {
        if (databaseFile == null) {
            throw new IllegalArgumentException("Не указан файл SQLite");
        }
        // Соединение открывается лениво: резервная копия должна быть снята
        // до того, как SqliteStorage мигрирует старую схему.
        this.databaseFile = databaseFile.toAbsolutePath();
    }

    /** Открывает базу и при необходимости обновляет схему. */
    private Connection connection() {
        if (connection != null) return connection;
        try {
            Path parent = databaseFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            // Открытие через SqliteStorage гарантирует актуальную схему и
            // миграцию старых баз; соединение закрывается сразу.
            try (SqliteStorage ignored = new SqliteStorage(databaseFile)) {
                // только для побочного эффекта: createSchema/migrate
            }
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try (Statement statement = opened.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
            }
            connection = opened;
            return connection;
        } catch (IOException e) {
            throw new MigrationException("Не удалось создать каталог SQLite: "
                    + databaseFile.getParent(), e);
        } catch (SQLException | RuntimeException e) {
            throw new MigrationException("Не удалось открыть SQLite: "
                    + databaseFile + " (" + e.getMessage() + ")", e);
        }
    }

    public Path databaseFile() {
        return databaseFile;
    }

    @Override
    public String description() {
        return "sqlite:" + databaseFile;
    }

    @Override
    public MigrationData read() {
        synchronized (lock) {
            try {
                connection();
                return readUnlocked();
            } catch (SQLException | RuntimeException e) {
                throw new MigrationException("Не удалось прочитать SQLite "
                        + databaseFile + ": " + e.getMessage(), e);
            }
        }
    }

    private MigrationData readUnlocked() throws SQLException {
        Map<String, List<String>> members = new LinkedHashMap<>();
        Map<String, Map<String, String>> roles = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT board_id, user_id, role FROM board_members ORDER BY rowid
                     """)) {
            while (rs.next()) {
                String boardId = rs.getString("board_id");
                String userId = rs.getString("user_id");
                members.computeIfAbsent(boardId, key -> new ArrayList<>()).add(userId);
                roles.computeIfAbsent(boardId, key -> new LinkedHashMap<>())
                        .put(userId, rs.getString("role"));
            }
        }

        Map<String, List<ElementRecord>> elements = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM elements ORDER BY rowid")) {
            while (rs.next()) {
                elements.computeIfAbsent(rs.getString("board_id"), key -> new ArrayList<>())
                        .add(new ElementRecord(
                                rs.getString("element_id"),
                                rs.getString("element_type"),
                                new JSONObject(orEmptyJson(rs.getString("data"))),
                                rs.getString("added_by"),
                                rs.getLong("updated_at")));
            }
        }

        List<BoardSnapshot> boards = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM boards ORDER BY rowid")) {
            while (rs.next()) {
                String id = rs.getString("id");
                BoardRecord board = new BoardRecord(
                        id,
                        rs.getString("name"),
                        rs.getString("owner_id"),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"),
                        members.getOrDefault(id, List.of()),
                        roles.getOrDefault(id, Map.of()));
                boards.add(new BoardSnapshot(board,
                        elements.getOrDefault(id, List.of())));
            }
        }

        List<InviteRecord> invites = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM invites ORDER BY rowid")) {
            while (rs.next()) {
                invites.add(new InviteRecord(
                        rs.getString("code"),
                        rs.getString("board_id"),
                        rs.getString("created_by"),
                        rs.getString("role"),
                        rs.getLong("created_at"),
                        rs.getLong("expires_at"),
                        rs.getInt("max_uses"),
                        rs.getInt("used_count")));
            }
        }

        List<DeviceRecord> devices = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM devices ORDER BY rowid")) {
            while (rs.next()) {
                devices.add(new DeviceRecord(
                        rs.getString("id"),
                        rs.getString("username"),
                        blankToNull(rs.getString("email")),
                        blankToNull(rs.getString("password_hash")),
                        blankToNull(rs.getString("device_key_hash")),
                        rs.getString("color"),
                        rs.getString("avatar"),
                        rs.getLong("created_at")));
            }
        }

        return new MigrationData(boards, invites, devices);
    }

    @Override
    public WriteResult write(MigrationData data) {
        List<String> warnings = new ArrayList<>();
        Set<String> boardIds = new HashSet<>();
        for (BoardSnapshot board : data.boards()) boardIds.add(board.id());

        synchronized (lock) {
            try {
                connection();
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    for (BoardSnapshot snapshot : data.boards()) {
                        writeBoard(snapshot);
                    }
                    int skippedInvites = 0;
                    for (InviteRecord invite : data.invites()) {
                        if (invite.boardId() == null || !boardIds.contains(invite.boardId())) {
                            skippedInvites++;
                            continue;
                        }
                        writeInvite(invite);
                    }
                    if (skippedInvites > 0) {
                        warnings.add("Пропущено приглашений без доски: " + skippedInvites);
                    }
                    for (DeviceRecord device : data.devices()) {
                        writeDevice(device);
                    }
                    connection.commit();
                    WriteResult totals = WriteResult.identity(data, warnings);
                    return new WriteResult(totals.boardIds(), totals.userIds(),
                            totals.boards(), totals.elements(),
                            totals.invites() - skippedInvites, totals.devices(),
                            totals.warnings());
                } catch (SQLException | RuntimeException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                        // соединение всё равно будет закрыто
                    }
                    throw e;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                throw new MigrationException("Не удалось записать SQLite "
                        + databaseFile + ": " + e.getMessage(), e);
            }
        }
    }

    private void writeBoard(BoardSnapshot snapshot) throws SQLException {
        BoardRecord board = snapshot.board();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO boards(id, name, owner_id, width, height, created_at, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name       = excluded.name,
                    owner_id   = excluded.owner_id,
                    width      = excluded.width,
                    height     = excluded.height,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, board.id());
            ps.setString(2, board.name() == null ? "" : board.name());
            ps.setString(3, board.ownerId() == null ? "" : board.ownerId());
            ps.setInt(4, board.width());
            ps.setInt(5, board.height());
            ps.setLong(6, board.createdAt());
            ps.setLong(7, board.updatedAt());
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM board_members WHERE board_id = ?")) {
            ps.setString(1, board.id());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO board_members(board_id, user_id, role) VALUES(?, ?, ?)
                ON CONFLICT(board_id, user_id) DO UPDATE SET role = excluded.role
                """)) {
            for (String member : board.memberIds()) {
                if (member == null) continue;
                ps.setString(1, board.id());
                ps.setString(2, member);
                ps.setString(3, board.memberRoles().getOrDefault(member, "editor"));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM elements WHERE board_id = ?")) {
            ps.setString(1, board.id());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO elements(board_id, element_id, element_type, data,
                                     added_by, updated_at)
                VALUES(?, ?, ?, ?, ?, ?)
                """)) {
            for (ElementRecord element : snapshot.elements()) {
                ps.setString(1, board.id());
                ps.setString(2, element.elementId());
                ps.setString(3, element.elementType());
                ps.setString(4, element.data().toString());
                ps.setString(5, element.addedBy() == null ? "" : element.addedBy());
                ps.setLong(6, element.updatedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeInvite(InviteRecord invite) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO invites(code, board_id, created_by, role,
                                    created_at, expires_at, max_uses, used_count)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    board_id   = excluded.board_id,
                    created_by = excluded.created_by,
                    role       = excluded.role,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at,
                    max_uses   = excluded.max_uses,
                    used_count = excluded.used_count
                """)) {
            ps.setString(1, invite.code());
            ps.setString(2, invite.boardId());
            ps.setString(3, invite.createdBy() == null ? "" : invite.createdBy());
            ps.setString(4, invite.role() == null ? "editor" : invite.role());
            ps.setLong(5, invite.createdAt());
            ps.setLong(6, invite.expiresAt());
            ps.setInt(7, invite.maxUses());
            ps.setInt(8, invite.usedCount());
            ps.executeUpdate();
        }
    }

    private void writeDevice(DeviceRecord device) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO devices(id, username, email, password_hash,
                                    device_key_hash, color, avatar, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    username        = excluded.username,
                    email           = excluded.email,
                    password_hash   = excluded.password_hash,
                    device_key_hash = excluded.device_key_hash,
                    color           = excluded.color,
                    avatar          = excluded.avatar,
                    created_at      = excluded.created_at
                """)) {
            ps.setString(1, device.id());
            ps.setString(2, device.username() == null ? "" : device.username());
            ps.setString(3, device.email());
            ps.setString(4, device.passwordHash());
            ps.setString(5, device.deviceKeyHash());
            ps.setString(6, device.color());
            ps.setString(7, device.avatar());
            ps.setLong(8, device.createdAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("unique") || message.contains("device_key_hash")) {
                throw new MigrationException("Устройство " + device.id()
                        + ": ключ устройства уже занят другим устройством. "
                        + "Удалите дубликат в назначении и повторите перенос.", e);
            }
            throw new MigrationException("Не удалось записать устройство "
                    + device.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<Path> backup(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (!Files.exists(databaseFile)) {
            return List.of();
        }
        try (Connection copyConnection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile);
             Statement statement = copyConnection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            throw new MigrationException("Не удалось подготовить SQLite к копии: "
                    + e.getMessage(), e);
        }
        Path copy = directory.resolve("slate-" + System.currentTimeMillis() + ".db");
        Files.copy(databaseFile, copy, StandardCopyOption.REPLACE_EXISTING);
        return List.of(copy);
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection == null) return;
            try {
                connection.close();
            } catch (SQLException ignored) {
                // закрываем максимум возможного
            } finally {
                connection = null;
            }
        }
    }

    private static String orEmptyJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
