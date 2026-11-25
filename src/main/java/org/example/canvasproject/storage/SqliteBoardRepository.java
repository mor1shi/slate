package org.example.canvasproject.storage;

import org.json.JSONArray;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Локальное хранилище по умолчанию: один файл SQLite, без сервера и установки.
 * Схема версионируется через `PRAGMA user_version`, чтобы будущие миграции
 * могли выполняться при открытии.
 */
public final class SqliteBoardRepository implements BoardRepository {

    private static final int SCHEMA_VERSION = 1;
    private static final int MIN_WIDTH = 400;
    private static final int MAX_SIZE = 4000;
    private static final int MIN_HEIGHT = 300;

    private final Connection connection;

    public SqliteBoardRepository(Path database) {
        try {
            Path parent = database.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            migrate();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось открыть локальное хранилище", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS boards (
                        id            TEXT PRIMARY KEY,
                        name          TEXT NOT NULL,
                        width         INTEGER NOT NULL,
                        height        INTEGER NOT NULL,
                        created_at    INTEGER NOT NULL,
                        updated_at    INTEGER NOT NULL,
                        elements_json TEXT NOT NULL DEFAULT '[]'
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS boards_updated ON boards(updated_at DESC)");
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            if (rs.next() && rs.getInt(1) < SCHEMA_VERSION) {
                try (Statement up = connection.createStatement()) {
                    up.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                }
            }
        }
    }

    @Override
    public List<LocalBoard> list() {
        List<LocalBoard> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM boards ORDER BY updated_at DESC, rowid DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(read(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать локальные доски", e);
        }
        return result;
    }

    @Override
    public Optional<LocalBoard> find(String id) {
        if (id == null) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM boards WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать доску", e);
        }
    }

    @Override
    public LocalBoard create(String name, int width, int height) {
        long now = System.currentTimeMillis();
        LocalBoard board = new LocalBoard(UUID.randomUUID().toString(), cleanName(name),
                clamp(width, MIN_WIDTH, MAX_SIZE),
                clamp(height, MIN_HEIGHT, MAX_SIZE),
                now, now, new JSONArray());
        save(board);
        return board;
    }

    @Override
    public void save(LocalBoard board) {
        if (board == null || board.getId() == null || board.getId().isBlank()) {
            throw new IllegalArgumentException("У доски нет id");
        }
        board.setName(cleanName(board.getName()));
        board.setWidth(clamp(board.getWidth(), MIN_WIDTH, MAX_SIZE));
        board.setHeight(clamp(board.getHeight(), MIN_HEIGHT, MAX_SIZE));
        board.setUpdatedAt(System.currentTimeMillis());

        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO boards(id, name, width, height, created_at, updated_at, elements_json)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name=excluded.name,
                    width=excluded.width,
                    height=excluded.height,
                    updated_at=excluded.updated_at,
                    elements_json=excluded.elements_json
                """)) {
            ps.setString(1, board.getId());
            ps.setString(2, board.getName());
            ps.setInt(3, board.getWidth());
            ps.setInt(4, board.getHeight());
            ps.setLong(5, board.getCreatedAt());
            ps.setLong(6, board.getUpdatedAt());
            ps.setString(7, board.getElements().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить доску", e);
        }
    }

    @Override
    public boolean delete(String id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM boards WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить доску", e);
        }
    }

    @Override
    public boolean rename(String id, String newName) {
        String name = cleanName(newName);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE boards SET name = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось переименовать доску", e);
        }
    }

    private LocalBoard read(ResultSet rs) throws SQLException {
        return new LocalBoard(
                rs.getString("id"),
                rs.getString("name"),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                new JSONArray(rs.getString("elements_json")));
    }

    private static String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) value = "Без названия";
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
