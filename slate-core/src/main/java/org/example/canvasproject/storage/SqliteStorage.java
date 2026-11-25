package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONObject;

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

/**
 * Локальная база по умолчанию: один файл SQLite в режиме WAL.
 *
 * <p>Схема версионируется через `PRAGMA user_version`. Версия 1 — первая
 * локальная схема с элементами в JSON-колонке; она мигрирует в версию 2
 * (отдельные таблицы элементов, участников и приглашений).
 */
public final class SqliteStorage implements Storage {

    public static final int SCHEMA_VERSION = 2;

    private final SqliteDb db;
    private final Connection connection;
    private final SqliteBoardRepository boards;
    private final SqliteElementRepository elements;
    private final SqliteInviteRepository invites;
    private final SqliteDeviceRepository devices;
    private final FileAssetStore assets;

    public SqliteStorage(Path databaseFile) {
        try {
            Path absolute = databaseFile.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            db = new SqliteDb(connection);
            migrate();
            boards = new SqliteBoardRepository(db);
            elements = new SqliteElementRepository(db);
            invites = new SqliteInviteRepository(db);
            devices = new SqliteDeviceRepository(db);
            assets = new FileAssetStore(parent == null ? Path.of("assets") : parent.resolve("assets"));
        } catch (Exception e) {
            throw new StorageException("Не удалось открыть SQLite: " + databaseFile, e);
        }
    }

    private void migrate() throws SQLException {
        int version = userVersion(connection);
        if (version >= SCHEMA_VERSION) {
            createSchema(connection);
            return;
        }
        boolean legacy = version <= 1 && hasColumn(connection, "boards", "elements_json");
        List<LegacyBoard> legacyBoards = legacy ? readLegacyBoards(connection) : List.of();

        db.write(conn -> {
            if (legacy) {
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS boards_legacy");
                    st.execute("ALTER TABLE boards RENAME TO boards_legacy");
                }
            }
            createSchema(conn);
            for (LegacyBoard old : legacyBoards) {
                insertLegacy(conn, old);
            }
            try (Statement st = conn.createStatement()) {
                if (legacy) st.execute("DROP TABLE IF EXISTS boards_legacy");
                st.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
            return null;
        });
    }

    private record LegacyBoard(String id, String name, int width, int height,
                               long createdAt, long updatedAt, String elementsJson) {}

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private static int userVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static List<LegacyBoard> readLegacyBoards(Connection conn) throws SQLException {
        List<LegacyBoard> result = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM boards")) {
            while (rs.next()) {
                result.add(new LegacyBoard(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"),
                        rs.getString("elements_json")));
            }
        }
        return result;
    }

    private static void insertLegacy(Connection conn, LegacyBoard old) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO boards(id, name, owner_id, width, height, created_at, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, old.id());
            ps.setString(2, old.name());
            ps.setString(3, BoardRecord.LOCAL_OWNER);
            ps.setInt(4, old.width());
            ps.setInt(5, old.height());
            ps.setLong(6, old.createdAt());
            ps.setLong(7, old.updatedAt());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO board_members(board_id, user_id, role) VALUES(?, ?, 'owner')
                """)) {
            ps.setString(1, old.id());
            ps.setString(2, BoardRecord.LOCAL_OWNER);
            ps.executeUpdate();
        }
        if (old.elementsJson() == null || old.elementsJson().isBlank()) return;
        JSONArray array = new JSONArray(old.elementsJson());
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO elements(board_id, element_id, element_type, data, added_by, updated_at)
                VALUES(?, ?, ?, ?, '', ?)
                """)) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject el = array.optJSONObject(i);
                if (el == null) continue;
                ps.setString(1, old.id());
                ps.setString(2, el.optString("elementId"));
                ps.setString(3, el.optString("elementType", "figure"));
                ps.setString(4, el.optJSONObject("data") == null
                        ? "{}" : el.getJSONObject("data").toString());
                ps.setLong(5, old.updatedAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS boards (
                        id         TEXT PRIMARY KEY,
                        name       TEXT NOT NULL,
                        owner_id   TEXT NOT NULL DEFAULT '',
                        width      INTEGER NOT NULL DEFAULT 1920,
                        height     INTEGER NOT NULL DEFAULT 1080,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS board_members (
                        board_id TEXT NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
                        user_id  TEXT NOT NULL,
                        role     TEXT NOT NULL DEFAULT 'editor',
                        PRIMARY KEY (board_id, user_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS elements (
                        board_id     TEXT NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
                        element_id   TEXT NOT NULL,
                        element_type TEXT NOT NULL,
                        data         TEXT NOT NULL DEFAULT '{}',
                        added_by     TEXT NOT NULL DEFAULT '',
                        updated_at   INTEGER NOT NULL,
                        PRIMARY KEY (board_id, element_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS invites (
                        code       TEXT PRIMARY KEY,
                        board_id   TEXT NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
                        created_by TEXT NOT NULL DEFAULT '',
                        role       TEXT NOT NULL DEFAULT 'editor',
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        max_uses   INTEGER NOT NULL DEFAULT 1,
                        used_count INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS devices (
                        id              TEXT PRIMARY KEY,
                        username        TEXT NOT NULL,
                        email           TEXT,
                        password_hash   TEXT,
                        device_key_hash TEXT UNIQUE,
                        color           TEXT,
                        avatar          TEXT,
                        created_at      INTEGER NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS board_members_user ON board_members(user_id)");
            st.execute("CREATE INDEX IF NOT EXISTS elements_board ON elements(board_id)");
            st.execute("CREATE INDEX IF NOT EXISTS invites_board ON invites(board_id)");
            st.execute("CREATE INDEX IF NOT EXISTS devices_username ON devices(username)");
        }
    }

    @Override public BoardRepository boards() { return boards; }
    @Override public ElementRepository elements() { return elements; }
    @Override public InviteRepository invites() { return invites; }
    @Override public DeviceRepository devices() { return devices; }
    @Override public AssetStore assets() { return assets; }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
