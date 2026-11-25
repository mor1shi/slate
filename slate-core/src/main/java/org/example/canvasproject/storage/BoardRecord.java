package org.example.canvasproject.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Доска в хранилище. Не зависит от Mongo/JavaFX: одна и та же запись
 * используется SQLite, Mongo-адаптером и форматом `.slate`.
 */
public record BoardRecord(String id, String name, String ownerId,
                          int width, int height, long createdAt, long updatedAt,
                          List<String> memberIds, Map<String, String> memberRoles) {

    public static final String LOCAL_OWNER = "local";

    public BoardRecord {
        memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
        memberRoles = memberRoles == null ? Map.of() : Map.copyOf(memberRoles);
    }

    public static BoardRecord draft(String name, String ownerId, int width, int height) {
        long now = System.currentTimeMillis();
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put(ownerId, "owner");
        return new BoardRecord(null, name, ownerId, width, height, now, now,
                List.of(ownerId), roles);
    }

    /** Роль участника или null, если он не приглашён. Отсутствие роли = editor. */
    public String roleOf(String userId) {
        if (userId == null) return null;
        if (userId.equals(ownerId)) return "owner";
        if (!memberIds.contains(userId)) return null;
        String role = memberRoles.get(userId);
        return role == null ? "editor" : role;
    }

    public boolean canEdit(String userId) {
        String role = roleOf(userId);
        return "owner".equals(role) || "editor".equals(role);
    }

    public boolean isOwner(String userId) {
        return userId != null && userId.equals(ownerId);
    }

    public BoardRecord withMembers(List<String> ids, Map<String, String> roles) {
        return new BoardRecord(id, name, ownerId, width, height, createdAt, updatedAt,
                ids == null ? memberIds : new ArrayList<>(ids),
                roles == null ? memberRoles : new LinkedHashMap<>(roles));
    }
}
