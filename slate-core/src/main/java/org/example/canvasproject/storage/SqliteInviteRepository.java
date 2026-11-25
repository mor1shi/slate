package org.example.canvasproject.storage;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Приглашения в SQLite. consume() — одна транзакция с условным UPDATE. */
final class SqliteInviteRepository implements InviteRepository {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SqliteDb db;

    SqliteInviteRepository(SqliteDb db) {
        this.db = db;
    }

    @Override
    public InviteRecord create(String boardId, String createdBy, String role,
                               long ttlMillis, int maxUses) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("Не указана доска");
        }
        long now = System.currentTimeMillis();
        long ttl = Math.max(60_000L, Math.min(ttlMillis, 24 * 60 * 60 * 1000L));
        int uses = Math.max(1, Math.min(maxUses, 50));
        String cleanRole = "viewer".equals(role) ? "viewer" : "editor";
        return db.write(conn -> {
            try (PreparedStatement exists = conn.prepareStatement(
                    "SELECT 1 FROM boards WHERE id = ?")) {
                exists.setString(1, boardId);
                try (ResultSet rs = exists.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Доска не найдена");
                }
            }
            for (int attempt = 0; attempt < 10; attempt++) {
                String code = generateCode();
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO invites(code, board_id, created_by, role,
                                            created_at, expires_at, max_uses, used_count)
                        VALUES(?, ?, ?, ?, ?, ?, ?, 0)
                        """)) {
                    ps.setString(1, code);
                    ps.setString(2, boardId);
                    ps.setString(3, createdBy == null ? "" : createdBy);
                    ps.setString(4, cleanRole);
                    ps.setLong(5, now);
                    ps.setLong(6, now + ttl);
                    ps.setInt(7, uses);
                    ps.executeUpdate();
                    return new InviteRecord(code, boardId, createdBy, cleanRole,
                            now, now + ttl, uses, 0);
                } catch (SQLException duplicate) {
                    if (!isUniqueViolation(duplicate)) throw duplicate;
                }
            }
            throw new StorageException("Не удалось создать уникальный код приглашения");
        });
    }

    @Override
    public Optional<InviteRecord> find(String code) {
        if (code == null) return Optional.empty();
        return db.read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM invites WHERE code = ?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<InviteRecord> consume(String code) {
        if (code == null) return Optional.empty();
        long now = System.currentTimeMillis();
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE invites SET used_count = used_count + 1
                    WHERE code = ? AND used_count < max_uses AND expires_at > ?
                    """)) {
                ps.setString(1, code);
                ps.setLong(2, now);
                if (ps.executeUpdate() == 0) return Optional.empty();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM invites WHERE code = ?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public boolean revoke(String code, String boardId) {
        if (code == null || boardId == null) return false;
        return db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM invites WHERE code = ? AND board_id = ?")) {
                ps.setString(1, code);
                ps.setString(2, boardId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void deleteByBoard(String boardId) {
        if (boardId == null) return;
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM invites WHERE board_id = ?")) {
                ps.setString(1, boardId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static InviteRecord read(ResultSet rs) throws SQLException {
        return new InviteRecord(
                rs.getString("code"),
                rs.getString("board_id"),
                rs.getString("created_by"),
                rs.getString("role"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                rs.getInt("max_uses"),
                rs.getInt("used_count"));
    }

    private static String generateCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static boolean isUniqueViolation(SQLException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("unique");
    }
}
