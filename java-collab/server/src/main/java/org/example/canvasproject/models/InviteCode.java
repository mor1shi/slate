package org.example.canvasproject.models;

import org.bson.types.ObjectId;

public class InviteCode {
    private ObjectId id;
    private String code;         // 8-символьный код, напр. "XK7Q2MAB"
    private String canvasId;
    private String createdBy;
    private String role;         // editor | viewer
    private long expiresAt;      // Unix timestamp (ms)
    private int maxUses;
    private int usedCount;

    public InviteCode() {}

    public InviteCode(String code, String canvasId, String createdBy, long ttlMillis, int maxUses) {
        this(code, canvasId, createdBy, CanvasDocument.ROLE_EDITOR, ttlMillis, maxUses);
    }

    public InviteCode(String code, String canvasId, String createdBy, String role,
                      long ttlMillis, int maxUses) {
        this.code = code;
        this.canvasId = canvasId;
        this.createdBy = createdBy;
        this.role = role;
        this.expiresAt = System.currentTimeMillis() + ttlMillis;
        this.maxUses = maxUses;
        this.usedCount = 0;
    }

    public boolean isValid() {
        return System.currentTimeMillis() < expiresAt && (maxUses <= 0 || usedCount < maxUses);
    }

    public ObjectId getId() { return id; }
    public void setId(ObjectId id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getCanvasId() { return canvasId; }
    public void setCanvasId(String canvasId) { this.canvasId = canvasId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getRole() { return role == null ? CanvasDocument.ROLE_EDITOR : role; }
    public void setRole(String role) { this.role = role; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
}
