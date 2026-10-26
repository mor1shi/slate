package org.example.canvasproject.models;

import org.bson.types.ObjectId;

public class InviteCode {
    private ObjectId id;
    private String code;         // 6-символьный код, напр. "XK7Q2M"
    private String canvasId;
    private String createdBy;
    private long expiresAt;      // Unix timestamp (ms)
    private int maxUses;
    private int usedCount;

    public InviteCode() {}

    public InviteCode(String code, String canvasId, String createdBy, long ttlMillis, int maxUses) {
        this.code = code;
        this.canvasId = canvasId;
        this.createdBy = createdBy;
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
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
}
