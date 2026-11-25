package org.example.canvasproject.storage;

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

/**
 * Снимки досок и служебные флаги в том же файле SQLite, что и основные данные.
 *
 * <p>Схема создаётся отдельным `CREATE TABLE IF NOT EXISTS` и не трогает
 * `PRAGMA user_version` хранилища: старые базы открываются как раньше. Снимок
 * — полный JSON доски (`board.json` из `.slate`), поэтому восстановление не
 * зависит от текущей схемы элементов.
 */
public final class SnapshotStore implements AutoCloseable {

    public static final int DEFAULT_LIMIT = 10;

    /** Флаг первого запуска: доска «Без названия» создаётся один раз. */
    public static final String FLAG_WELCOME_BOARD = "welcome-board-created";

    public record Snapshot(long id, String boardId, long createdAt, String reason,
                           String payload) {}

    private final Connection connection;

    public SnapshotStore(Path databaseFile) {
        try {
            Path absolute = databaseFile.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS board_snapshots (
                            id         INTEGER PRIMARY KEY AUTOINCREMENT,
                            board_id   TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            reason     TEXT NOT NULL DEFAULT '',
                            payload    TEXT NOT NULL
                        )
                        """);
                st.execute("""
                        CREATE INDEX IF NOT EXISTS board_snapshots_board
                        ON board_snapshots(board_id, created_at)
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS app_flags (
                            key   TEXT PRIMARY KEY,
                            value TEXT NOT NULL DEFAULT ''
                        )
                        """);
            }
        } catch (Exception e) {
            throw new StorageException("Не удалось открыть хранилище снимков", e);
        }
    }

    /**
     * Сохраняет снимок и удаляет старые сверх лимита — одной транзакцией,
     * чтобы при аварии не остаться без истории.
     */
    public synchronized Snapshot save(String boardId, String reason, String payload,
                                      int limit) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("Нет доски для снимка");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Пустой снимок");
        }
        int keep = Math.max(1, limit);
        long now = System.currentTimeMillis();
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO board_snapshots(board_id, created_at, reason, payload)
                        VALUES(?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, boardId);
                    ps.setLong(2, now);
                    ps.setString(3, reason == null ? "" : reason);
                    ps.setString(4, payload);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        id = keys.next() ? keys.getLong(1) : -1;
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement("""
                        DELETE FROM board_snapshots
                        WHERE board_id = ? AND id NOT IN (
                            SELECT id FROM board_snapshots
                            WHERE board_id = ?
                            ORDER BY created_at DESC, id DESC
                            LIMIT ?
                        )
                        """)) {
                    ps.setString(1, boardId);
                    ps.setString(2, boardId);
                    ps.setInt(3, keep);
                    ps.executeUpdate();
                }
                connection.commit();
                return new Snapshot(id, boardId, now,
                        reason == null ? "" : reason, payload);
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("Не удалось сохранить снимок доски", e);
        }
    }

    /** Снимки доски, новые сверху. */
    public synchronized List<Snapshot> list(String boardId) {
        if (boardId == null) return List.of();
        List<Snapshot> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM board_snapshots WHERE board_id = ?
                ORDER BY created_at DESC, id DESC
                """)) {
            ps.setString(1, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(read(rs));
            }
        } catch (SQLException e) {
            throw new StorageException("Не удалось прочитать снимки доски", e);
        }
        return result;
    }

    public synchronized Optional<Snapshot> latest(String boardId) {
        List<Snapshot> all = list(boardId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public synchronized int count(String boardId) {
        return list(boardId).size();
    }

    public synchronized void deleteFor(String boardId) {
        if (boardId == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM board_snapshots WHERE board_id = ?")) {
            ps.setString(1, boardId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Не удалось удалить снимки доски", e);
        }
    }

    public synchronized boolean hasFlag(String key) {
        if (key == null) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM app_flags WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StorageException("Не удалось прочитать флаг", e);
        }
    }

    public synchronized void setFlag(String key) {
        if (key == null || key.isBlank()) return;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO app_flags(key, value) VALUES(?, '')
                ON CONFLICT(key) DO NOTHING
                """)) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Не удалось сохранить флаг", e);
        }
    }

    private static Snapshot read(ResultSet rs) throws SQLException {
        return new Snapshot(rs.getLong("id"), rs.getString("board_id"),
                rs.getLong("created_at"), rs.getString("reason"), rs.getString("payload"));
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
