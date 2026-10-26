package org.example.canvasproject.service;

import org.example.canvasproject.models.CanvasDocument;
import org.example.canvasproject.models.InviteCode;

public class InviteService {

    private static InviteService instance;
    private final MongoDBService mongo;
    private final AuthService auth;

    private InviteService() {
        this.mongo = MongoDBService.getInstance();
        this.auth  = AuthService.getInstance();
    }

    public static synchronized InviteService getInstance() {
        if (instance == null) instance = new InviteService();
        return instance;
    }

    public InviteCode generateCode(String canvasId, String requestingUserId) {
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null) return null;
        if (!canvas.getMemberIds().contains(requestingUserId)) return null;

        return mongo.createInviteCode(canvasId, requestingUserId);
    }

    public String acceptCode(String code, String userId) {
        InviteCode invite = mongo.findInviteCode(code.toUpperCase());
        if (invite == null) return null;
        if (!invite.isValid()) return null;

        CanvasDocument canvas = mongo.findCanvasById(invite.getCanvasId());
        if (canvas == null) return null;

        if (!canvas.getMemberIds().contains(userId)) {
            mongo.addMemberToCanvas(invite.getCanvasId(), userId);
            mongo.incrementInviteUsage(code.toUpperCase());
        }

        return invite.getCanvasId();
    }
}
