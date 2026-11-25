package org.example.canvasproject.service.repository;

import org.example.canvasproject.models.InviteCode;
import org.example.canvasproject.service.MongoDBService;
import org.example.canvasproject.storage.InviteRecord;
import org.example.canvasproject.storage.InviteRepository;

import java.util.Optional;

/** Mongo-адаптер InviteRepository; consume остаётся атомарным findOneAndUpdate. */
final class MongoInviteRepository implements InviteRepository {

    private final MongoDBService mongo;

    MongoInviteRepository(MongoDBService mongo) {
        this.mongo = mongo;
    }

    @Override
    public InviteRecord create(String boardId, String createdBy, String role,
                               long ttlMillis, int maxUses) {
        long createdAt = System.currentTimeMillis();
        InviteCode code = mongo.createInviteCode(boardId, createdBy, role, ttlMillis, maxUses);
        return toRecord(code, createdAt);
    }

    @Override
    public Optional<InviteRecord> find(String code) {
        InviteCode found = mongo.findInviteCode(code);
        return found == null ? Optional.empty()
                : Optional.of(toRecord(found, 0L));
    }

    @Override
    public Optional<InviteRecord> consume(String code) {
        InviteCode consumed = mongo.consumeInvite(code);
        return consumed == null ? Optional.empty()
                : Optional.of(toRecord(consumed, 0L));
    }

    @Override
    public boolean revoke(String code, String boardId) {
        return mongo.revokeInvite(code, boardId);
    }

    @Override
    public void deleteByBoard(String boardId) {
        mongo.deleteInvitesForCanvas(boardId);
    }

    private static InviteRecord toRecord(InviteCode code, long createdAt) {
        return new InviteRecord(code.getCode(), code.getCanvasId(), code.getCreatedBy(),
                code.getRole(), createdAt, code.getExpiresAt(), code.getMaxUses(),
                code.getUsedCount());
    }
}
