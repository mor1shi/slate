package org.example.canvasproject.storage;

/** Код приглашения. consume() увеличивает usedCount атомарно. */
public record InviteRecord(String code, String boardId, String createdBy, String role,
                           long createdAt, long expiresAt, int maxUses, int usedCount) {

    public boolean isUsable(long now) {
        return usedCount < maxUses && expiresAt > now;
    }
}
