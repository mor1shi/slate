package org.example.canvasproject.storage;

import java.util.Optional;

/** Коды приглашений. TTL и лимит использований проверяются хранилищем. */
public interface InviteRepository {

    InviteRecord create(String boardId, String createdBy, String role,
                        long ttlMillis, int maxUses);

    Optional<InviteRecord> find(String code);

    /** Атомарно расходует одно использование; пусто, если код недействителен. */
    Optional<InviteRecord> consume(String code);

    boolean revoke(String code, String boardId);

    void deleteByBoard(String boardId);
}
