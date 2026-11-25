package org.example.canvasproject.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Доски в SQLite: участники лежат отдельной таблицей, удаление каскадное. */
final class SqliteBoardRepository implements BoardRepository {

    private final SqliteDb db;

    SqliteBoardRepository(SqliteDb db) {
        this.db = db;
    }

    @Override
    public BoardRecord create(String name, String ownerId, int width, int height) {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String cleanName = cleanName(name);
        int w = dimension(width, 1920);
        int h = dimension(height, 1080);
        String owner = ownerId == null ? "" : ownerId;

        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO boards(id, name, owner_id, width, height, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, id);
                ps.setString(2, cleanName);
                ps.setString(3, owner);
                ps.setInt(4, w);
                ps.setInt(5, h);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            if (!owner.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO board_members(board_id, user_id, role) VALUES(?, ?, 'owner')
                        """)) {
                    ps.setString(1, id);
                    ps.setString(2, owner);
                    ps.executeUpdate();
                }
            }
            return new BoardRecord(id, cleanName, owner, w, h, now, now,
                    owner.isEmpty() ? List.of() : List.of(owner),
                    owner.isEmpty() ? Map.of() : Map.of(owner, "owner"));
        });
    }

    @Override
    public Optional<BoardRecord> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM boards WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(conn, rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<BoardRecord> listForUser(String userId) {
        if (userId == null) return List.of();
        return db.read(conn -> {
            List<BoardRecord> result = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT b.* FROM boards b
                    JOIN board_members m ON m.board_id = b.id
                    WHERE m.user_id = ?
                    ORDER BY b.updated_at DESC, b.rowid DESC
                    """)) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(read(conn, rs));
                }
            }
            return result;
        });
    }

    @Override
    public boolean rename(String id, String newName) {
        if (id == null) return false;
        String name = cleanName(newName);
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE boards SET name = ?, updated_at = ? WHERE id = ?
                    """)) {
                ps.setString(1, name);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean resize(String id, int width, int height) {
        if (id == null) return false;
        int w = dimension(width, 1920);
        int h = dimension(height, 1080);
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE boards SET width = ?, height = ?, updated_at = ? WHERE id = ?
                    """)) {
                ps.setInt(1, w);
                ps.setInt(2, h);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean delete(String id) {
        if (id == null) return false;
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM boards WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean addMember(String id, String userId, String role) {
        if (id == null || userId == null || userId.isBlank()) return false;
        String cleanRole = cleanRole(role);
        return db.write(conn -> {
            if (!boardExists(conn, id)) return false;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO board_members(board_id, user_id, role) VALUES(?, ?, ?)
                    ON CONFLICT(board_id, user_id) DO UPDATE SET role = excluded.role
                    """)) {
                ps.setString(1, id);
                ps.setString(2, userId);
                ps.setString(3, cleanRole);
                ps.executeUpdate();
            }
            touch(conn, id);
            return true;
        });
    }

    @Override
    public boolean removeMember(String id, String userId) {
        if (id == null || userId == null) return false;
        return db.write(conn -> {
            try (PreparedStatement owner = conn.prepareStatement(
                    "SELECT owner_id FROM boards WHERE id = ?")) {
                owner.setString(1, id);
                try (ResultSet rs = owner.executeQuery()) {
                    if (!rs.next()) return false;
                    if (userId.equals(rs.getString("owner_id"))) return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    DELETE FROM board_members WHERE board_id = ? AND user_id = ?
                    """)) {
                ps.setString(1, id);
                ps.setString(2, userId);
                boolean removed = ps.executeUpdate() > 0;
                if (removed) touch(conn, id);
                return removed;
            }
        });
    }

    @Override
    public boolean setRole(String id, String userId, String role) {
        if (id == null || userId == null) return false;
        String cleanRole = cleanRole(role);
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE board_members SET role = ? WHERE board_id = ? AND user_id = ?
                    """)) {
                ps.setString(1, cleanRole);
                ps.setString(2, id);
                ps.setString(3, userId);
                boolean updated = ps.executeUpdate() > 0;
                if (updated) touch(conn, id);
                return updated;
            }
        });
    }

    private static boolean boardExists(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM boards WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void touch(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE boards SET updated_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static BoardRecord read(Connection conn, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        List<String> members = new ArrayList<>();
        Map<String, String> roles = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT user_id, role FROM board_members WHERE board_id = ? ORDER BY rowid
                """)) {
            ps.setString(1, id);
            try (ResultSet m = ps.executeQuery()) {
                while (m.next()) {
                    String user = m.getString("user_id");
                    members.add(user);
                    roles.put(user, m.getString("role"));
                }
            }
        }
        return new BoardRecord(id, rs.getString("name"), rs.getString("owner_id"),
                rs.getInt("width"), rs.getInt("height"),
                rs.getLong("created_at"), rs.getLong("updated_at"), members, roles);
    }

    private static String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) value = "Без названия";
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static int dimension(int value, int fallback) {
        if (value < 1) return fallback;
        return Math.min(value, 20000);
    }

    private static String cleanRole(String role) {
        if ("owner".equals(role) || "viewer".equals(role) || "editor".equals(role)) return role;
        return "editor";
    }
}
