package org.example.canvasproject.storage;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Элементы в SQLite; пакетные операции выполняются одной транзакцией. */
final class SqliteElementRepository implements ElementRepository {

    private final SqliteDb db;

    SqliteElementRepository(SqliteDb db) {
        this.db = db;
    }

    @Override
    public List<ElementRecord> list(String boardId) {
        if (boardId == null) return List.of();
        return db.read(conn -> {
            List<ElementRecord> result = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT * FROM elements WHERE board_id = ? ORDER BY rowid
                    """)) {
                ps.setString(1, boardId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(read(rs));
                }
            }
            return result;
        });
    }

    @Override
    public Optional<ElementRecord> find(String boardId, String elementId) {
        if (boardId == null || elementId == null) return Optional.empty();
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT * FROM elements WHERE board_id = ? AND element_id = ?
                    """)) {
                ps.setString(1, boardId);
                ps.setString(2, elementId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void add(String boardId, ElementRecord element) {
        requireBoard(boardId);
        requireElement(element);
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO elements(board_id, element_id, element_type, data, added_by, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(board_id, element_id) DO UPDATE SET
                        element_type = excluded.element_type,
                        data         = excluded.data,
                        added_by     = excluded.added_by,
                        updated_at   = excluded.updated_at
                    """)) {
                ps.setString(1, boardId);
                ps.setString(2, element.elementId());
                ps.setString(3, element.elementType());
                ps.setString(4, element.data().toString());
                ps.setString(5, element.addedBy() == null ? "" : element.addedBy());
                ps.setLong(6, element.updatedAt() == 0
                        ? System.currentTimeMillis() : element.updatedAt());
                ps.executeUpdate();
            }
            touch(conn, boardId);
            return null;
        });
    }

    @Override
    public boolean update(String boardId, String elementId, JSONObject data) {
        if (boardId == null || elementId == null || data == null) return false;
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE elements SET data = ?, updated_at = ?
                    WHERE board_id = ? AND element_id = ?
                    """)) {
                ps.setString(1, data.toString());
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, boardId);
                ps.setString(4, elementId);
                boolean updated = ps.executeUpdate() > 0;
                if (updated) touch(conn, boardId);
                return updated;
            }
        });
    }

    @Override
    public boolean delete(String boardId, String elementId) {
        if (boardId == null || elementId == null) return false;
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    DELETE FROM elements WHERE board_id = ? AND element_id = ?
                    """)) {
                ps.setString(1, boardId);
                ps.setString(2, elementId);
                boolean deleted = ps.executeUpdate() > 0;
                if (deleted) touch(conn, boardId);
                return deleted;
            }
        });
    }

    @Override
    public void updateMany(String boardId, List<ElementUpdate> updates) {
        requireBoard(boardId);
        if (updates == null || updates.isEmpty()) return;
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (ElementUpdate update : updates) {
            if (update == null || update.elementId() == null || update.elementId().isBlank()) {
                throw new IllegalArgumentException("В пакете есть элемент без id");
            }
            if (update.data() == null) {
                throw new IllegalArgumentException("В пакете есть элемент без data");
            }
            if (!seen.add(update.elementId())) {
                throw new IllegalArgumentException("Повтор id в пакете: " + update.elementId());
            }
        }
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE elements SET data = ?, updated_at = ?
                    WHERE board_id = ? AND element_id = ?
                    """)) {
                for (ElementUpdate update : updates) {
                    ps.setString(1, update.data().toString());
                    ps.setLong(2, now);
                    ps.setString(3, boardId);
                    ps.setString(4, update.elementId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            touch(conn, boardId);
            return null;
        });
    }

    @Override
    public int deleteMany(String boardId, List<String> elementIds) {
        requireBoard(boardId);
        if (elementIds == null || elementIds.isEmpty()) return 0;
        Set<String> ids = new HashSet<>();
        for (String id : elementIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("В пакете есть пустой id");
            }
            ids.add(id);
        }
        return db.write(conn -> {
            int removed = 0;
            try (PreparedStatement ps = conn.prepareStatement("""
                    DELETE FROM elements WHERE board_id = ? AND element_id = ?
                    """)) {
                for (String id : ids) {
                    ps.setString(1, boardId);
                    ps.setString(2, id);
                    ps.addBatch();
                }
                for (int count : ps.executeBatch()) {
                    if (count > 0) removed += count;
                }
            }
            if (removed > 0) touch(conn, boardId);
            return removed;
        });
    }

    @Override
    public void replaceAll(String boardId, List<ElementRecord> elements) {
        requireBoard(boardId);
        List<ElementRecord> list = elements == null ? List.of() : elements;
        Set<String> seen = new HashSet<>();
        for (ElementRecord element : list) {
            requireElement(element);
            if (!seen.add(element.elementId())) {
                throw new IllegalArgumentException("Повтор id в наборе: " + element.elementId());
            }
        }
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM elements WHERE board_id = ?")) {
                ps.setString(1, boardId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO elements(board_id, element_id, element_type, data, added_by, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """)) {
                long now = System.currentTimeMillis();
                for (ElementRecord element : list) {
                    ps.setString(1, boardId);
                    ps.setString(2, element.elementId());
                    ps.setString(3, element.elementType());
                    ps.setString(4, element.data().toString());
                    ps.setString(5, element.addedBy() == null ? "" : element.addedBy());
                    ps.setLong(6, element.updatedAt() == 0 ? now : element.updatedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            touch(conn, boardId);
            return null;
        });
    }

    @Override
    public int count(String boardId) {        if (boardId == null) return 0;
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM elements WHERE board_id = ?")) {
                ps.setString(1, boardId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private static ElementRecord read(ResultSet rs) throws SQLException {
        String data = rs.getString("data");
        return new ElementRecord(
                rs.getString("element_id"),
                rs.getString("element_type"),
                new JSONObject(data == null || data.isBlank() ? "{}" : data),
                rs.getString("added_by"),
                rs.getLong("updated_at"));
    }

    private static void touch(Connection conn, String boardId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE boards SET updated_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, boardId);
            ps.executeUpdate();
        }
    }

    private static void requireBoard(String boardId) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("Не указана доска");
        }
    }

    private static void requireElement(ElementRecord element) {
        if (element == null || element.elementId() == null || element.elementId().isBlank()) {
            throw new IllegalArgumentException("У элемента нет id");
        }
        if (element.elementType() == null || element.elementType().isBlank()) {
            throw new IllegalArgumentException("У элемента нет типа");
        }
    }
}
