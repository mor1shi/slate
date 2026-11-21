package org.example.canvasproject.models;

import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CanvasDocument {
    /** Роли участников доски. Владелец всегда имеет роль owner. */
    public static final String ROLE_OWNER  = "owner";
    public static final String ROLE_EDITOR = "editor";
    public static final String ROLE_VIEWER = "viewer";

    private ObjectId id;
    private String name;
    private String ownerId;
    private List<String> memberIds = new ArrayList<>();
    /** userId → editor|viewer. Отсутствие записи у участника означает editor (миграция старых досок). */
    private Map<String, String> memberRoles = new HashMap<>();
    private List<CanvasElement> elements = new ArrayList<>();
    private long createdAt;
    private long updatedAt;
    private int canvasWidth  = 1920;
    private int canvasHeight = 1080;

    public CanvasDocument() {}

    public CanvasDocument(String name, String ownerId) {
        this.name = name;
        this.ownerId = ownerId;
        this.memberIds.add(ownerId);
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public ObjectId getId() { return id; }
    public void setId(ObjectId id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public List<String> getMemberIds() { return memberIds; }
    public void setMemberIds(List<String> memberIds) { this.memberIds = memberIds; }
    public Map<String, String> getMemberRoles() { return memberRoles; }
    public void setMemberRoles(Map<String, String> memberRoles) { this.memberRoles = memberRoles; }

    /** Роль пользователя на доске или null, если он не участник. */
    public String roleOf(String userId) {
        if (userId == null) return null;
        if (userId.equals(ownerId)) return ROLE_OWNER;
        if (!memberIds.contains(userId)) return null;
        String role = memberRoles.get(userId);
        return role == null ? ROLE_EDITOR : role;
    }

    public boolean canEdit(String userId) {
        String role = roleOf(userId);
        return ROLE_OWNER.equals(role) || ROLE_EDITOR.equals(role);
    }

    public boolean isOwner(String userId) {
        return userId != null && userId.equals(ownerId);
    }
    public List<CanvasElement> getElements() { return elements; }
    public void setElements(List<CanvasElement> elements) { this.elements = elements; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public int getCanvasWidth()  { return canvasWidth; }
    public void setCanvasWidth(int w)  { this.canvasWidth = w; }
    public int getCanvasHeight() { return canvasHeight; }
    public void setCanvasHeight(int h) { this.canvasHeight = h; }
}
