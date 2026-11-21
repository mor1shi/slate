package org.example.canvasproject.service;

import org.example.canvasproject.models.CanvasDocument;
import org.example.canvasproject.models.InviteCode;

/**
 * Коды приглашения.
 *
 * Правила выдачи:
 *  - владелец может пригласить редактора или наблюдателя;
 *  - редактор — только наблюдателя;
 *  - наблюдатель приглашать не может.
 * Код одноразовый по умолчанию, живёт 24 часа.
 */
public class InviteService {

    private static InviteService instance;
    private final MongoDBService mongo;

    private InviteService() {
        this.mongo = MongoDBService.getInstance();
    }

    public static synchronized InviteService getInstance() {
        if (instance == null) instance = new InviteService();
        return instance;
    }

    public InviteCode generateCode(String canvasId, String requestingUserId,
                                   String requestedRole, long ttlMillis, int maxUses) {
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null) return null;

        String myRole = canvas.roleOf(requestingUserId);
        if (myRole == null) return null;

        String role = CanvasDocument.ROLE_VIEWER.equals(requestedRole)
                ? CanvasDocument.ROLE_VIEWER
                : CanvasDocument.ROLE_EDITOR;

        if (!CanvasDocument.ROLE_OWNER.equals(myRole) && !CanvasDocument.ROLE_VIEWER.equals(role)) {
            return null;
        }

        return mongo.createInviteCode(canvasId, requestingUserId, role, ttlMillis, maxUses);
    }

    /** Возвращает id доски, если код принят. Роль берётся из самого кода. */
    public String acceptCode(String code, String userId) {
        InviteCode invite = mongo.findInviteCode(code);
        if (invite == null || !invite.isValid()) return null;

        CanvasDocument canvas = mongo.findCanvasById(invite.getCanvasId());
        if (canvas == null) return null;

        // Уже участник — просто открываем доску, код не тратим.
        if (canvas.getMemberIds().contains(userId)) {
            return invite.getCanvasId();
        }

        // Атомарно забираем одно использование: параллельные запросы не проскочат.
        InviteCode consumed = mongo.consumeInvite(code);
        if (consumed == null) return null;

        mongo.addMemberToCanvas(consumed.getCanvasId(), userId, consumed.getRole());
        return consumed.getCanvasId();
    }
}
