package org.example.canvasproject.server;

import org.bson.Document;
import org.example.canvasproject.models.*;
import org.example.canvasproject.service.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * WebSocket сервер
 *
 *   REGISTER         username, email, password
 *   LOGIN            username, password
 *   LOGOUT
 *   CREATE_CANVAS    name, canvasWidth, canvasHeight
 *   LIST_CANVASES
 *   JOIN_CANVAS      canvasId
 *   JOIN_BY_CODE     code
 *   LEAVE_CANVAS     canvasId
 *   RENAME_CANVAS    canvasId, name
 *   DELETE_CANVAS    canvasId
 *   GEN_INVITE       canvasId
 *   ADD_ELEMENT      canvasId, elementId, elementType, data
 *   DELETE_ELEMENT   canvasId, elementId
 *   UPDATE_ELEMENT   canvasId, elementId, data
 *   CURSOR_MOVE      canvasId, x, y
 */
public class CollaborationServer extends WebSocketServer {

    private static final Logger log = Logger.getLogger(CollaborationServer.class.getName());

    private final AuthService   auth   = AuthService.getInstance();
    private final MongoDBService mongo  = MongoDBService.getInstance();
    private final InviteService invite = InviteService.getInstance();

    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocket>> rooms = new ConcurrentHashMap<>();

    public CollaborationServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new Session());
        log.info("New connection: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = sessions.remove(conn);
        if (s != null && s.activeCanvasId != null) {
            leaveRoom(conn, s, s.activeCanvasId, true);
        }
        log.info("Disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.warning("WS error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        log.info("CollaborationServer started on port " + getPort());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject msg = new JSONObject(message);
            String type = msg.optString("type", "");

            switch (type) {
                case "REGISTER"       -> handleRegister(conn, msg);
                case "LOGIN"          -> handleLogin(conn, msg);
                case "LOGOUT"         -> handleLogout(conn, msg);
                case "CREATE_CANVAS"  -> handleCreateCanvas(conn, msg);
                case "LIST_CANVASES"  -> handleListCanvases(conn, msg);
                case "JOIN_CANVAS"    -> handleJoinCanvas(conn, msg);
                case "JOIN_BY_CODE"   -> handleJoinByCode(conn, msg);
                case "LEAVE_CANVAS"   -> handleLeaveCanvas(conn, msg);
                case "RENAME_CANVAS"  -> handleRenameCanvas(conn, msg);
                case "DELETE_CANVAS"  -> handleDeleteCanvas(conn, msg);
                case "GEN_INVITE"     -> handleGenInvite(conn, msg);
                case "ADD_ELEMENT"    -> handleAddElement(conn, msg);
                case "DELETE_ELEMENT" -> handleDeleteElement(conn, msg);
                case "UPDATE_ELEMENT" -> handleUpdateElement(conn, msg);
                case "CURSOR_MOVE"    -> handleCursorMove(conn, msg);
                default               -> send(conn, error("Unknown message type: " + type));
            }
        } catch (Exception e) {
            log.warning("Failed to process message: " + e.getMessage());
            send(conn, error("Server error: " + e.getMessage()));
        }
    }

    private void handleRegister(WebSocket conn, JSONObject msg) {
        String username = msg.optString("username");
        String email    = msg.optString("email", "").trim();
        String password = msg.optString("password");

        User user = auth.register(username, email, password);
        if (user == null) {
            send(conn, error("Registration failed: username already taken or password too short (min 6)"));
            return;
        }
        JSONObject resp = new JSONObject();
        resp.put("type",     "REGISTER_OK");
        resp.put("username", user.getUsername());
        send(conn, resp);
    }

    private void handleLogin(WebSocket conn, JSONObject msg) {
        String username = msg.optString("username");
        String password = msg.optString("password");
        String token    = auth.login(username, password);

        if (token == null) {
            send(conn, error("Invalid username or password"));
            return;
        }
        User user = auth.getUserByToken(token);
        Session s = sessions.get(conn);
        s.token    = token;
        s.userId   = user.getId().toHexString();
        s.username = user.getUsername();

        JSONObject resp = new JSONObject();
        resp.put("type",     "LOGIN_OK");
        resp.put("token",    token);
        resp.put("userId",   s.userId);
        resp.put("username", s.username);
        send(conn, resp);
    }

    private void handleLogout(WebSocket conn, JSONObject msg) {
        Session s = sessions.get(conn);
        if (s != null && s.token != null) {
            if (s.activeCanvasId != null) leaveRoom(conn, s, s.activeCanvasId, false);
            auth.logout(s.token);
            s.token    = null;
            s.userId   = null;
            s.username = null;
        }
        JSONObject resp = new JSONObject();
        resp.put("type", "LOGOUT_OK");
        send(conn, resp);
    }

    private void handleCreateCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String name = msg.optString("name", "Untitled Canvas");
        int w = Math.max(400, Math.min(4000, msg.optInt("canvasWidth",  1920)));
        int h = Math.max(300, Math.min(4000, msg.optInt("canvasHeight", 1080)));

        CanvasDocument canvas = mongo.createCanvas(name, s.userId, w, h);

        JSONObject resp = new JSONObject();
        resp.put("type",   "CANVAS_CREATED");
        resp.put("canvas", canvasToJson(canvas));
        send(conn, resp);
    }

    private void handleListCanvases(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        List<CanvasDocument> list = mongo.getCanvasesForUser(s.userId);
        JSONArray arr = new JSONArray();
        for (CanvasDocument c : list) arr.put(canvasToJson(c));

        JSONObject resp = new JSONObject();
        resp.put("type",     "CANVAS_LIST");
        resp.put("canvases", arr);
        send(conn, resp);
    }

    private void handleJoinCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null) { send(conn, error("Canvas not found")); return; }
        if (!canvas.getMemberIds().contains(s.userId)) {
            send(conn, error("Access denied: you are not a member of this canvas"));
            return;
        }

        if (s.activeCanvasId != null && !s.activeCanvasId.equals(canvasId)) {
            leaveRoom(conn, s, s.activeCanvasId, false);
        }

        joinRoom(conn, s, canvasId);

        JSONArray elements = new JSONArray();
        for (CanvasElement el : canvas.getElements()) {
            elements.put(elementToJson(el));
        }

        JSONObject resp = new JSONObject();
        resp.put("type",         "JOINED_CANVAS");
        resp.put("canvasId",     canvasId);
        resp.put("canvasName",   canvas.getName());
        resp.put("canvasWidth",  canvas.getCanvasWidth());
        resp.put("canvasHeight", canvas.getCanvasHeight());
        resp.put("ownerId",      canvas.getOwnerId());
        resp.put("elements",     elements);
        send(conn, resp);

        broadcast(canvasId, conn, userEvent("USER_JOINED", s));
    }

    private void handleJoinByCode(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String code = msg.optString("code", "").toUpperCase().trim();
        String canvasId = invite.acceptCode(code, s.userId);
        if (canvasId == null) {
            send(conn, error("Invalid or expired invite code"));
            return;
        }
        msg.put("canvasId", canvasId);
        handleJoinCanvas(conn, msg);
    }

    private void handleLeaveCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        leaveRoom(conn, s, canvasId, false);

        JSONObject resp = new JSONObject();
        resp.put("type", "LEFT_CANVAS");
        send(conn, resp);
    }

    private void handleRenameCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        String newName  = msg.optString("name", "").trim();
        if (newName.isEmpty()) { send(conn, error("Canvas name cannot be empty")); return; }

        boolean ok = mongo.renameCanvas(canvasId, newName, s.userId);
        if (!ok) {
            send(conn, error("Cannot rename: canvas not found or you are not the owner"));
            return;
        }

        JSONObject resp = new JSONObject();
        resp.put("type",      "CANVAS_RENAMED");
        resp.put("canvasId",  canvasId);
        resp.put("name",      newName);
        send(conn, resp);

        broadcast(canvasId, conn, resp);
    }

    private void handleDeleteCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        boolean ok = mongo.deleteCanvas(canvasId, s.userId);
        if (!ok) {
            send(conn, error("Cannot delete: canvas not found or you are not the owner"));
            return;
        }

        Set<WebSocket> room = rooms.remove(canvasId);
        if (room != null) {
            JSONObject kick = new JSONObject();
            kick.put("type",     "CANVAS_DELETED");
            kick.put("canvasId", canvasId);
            String payload = kick.toString();
            for (WebSocket ws : room) {
                if (ws.isOpen()) ws.send(payload);
                Session ws_s = sessions.get(ws);
                if (ws_s != null) ws_s.activeCanvasId = null;
            }
        }

        JSONObject resp = new JSONObject();
        resp.put("type",     "CANVAS_DELETED");
        resp.put("canvasId", canvasId);
        send(conn, resp);
    }

    private void handleGenInvite(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        InviteCode code = invite.generateCode(canvasId, s.userId);
        if (code == null) { send(conn, error("Failed to generate invite code")); return; }

        JSONObject resp = new JSONObject();
        resp.put("type",      "INVITE_CODE");
        resp.put("code",      code.getCode());
        resp.put("expiresAt", code.getExpiresAt());
        send(conn, resp);
    }

    private void handleAddElement(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId    = msg.optString("canvasId");
        if (!requireCanvasMember(conn, s, canvasId)) return;
        String elementId   = msg.optString("elementId");
        String elementType = msg.optString("elementType");
        JSONObject data    = msg.optJSONObject("data");
        if (data == null) data = new JSONObject();

        CanvasElement el = new CanvasElement(elementId, elementType, Document.parse(data.toString()), s.userId);
        mongo.addElementToCanvas(canvasId, el);

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",        "ELEMENT_ADDED");
        broadcast.put("canvasId",    canvasId);
        broadcast.put("elementId",   elementId);
        broadcast.put("elementType", elementType);
        broadcast.put("data",        data);
        broadcast.put("addedBy",     s.userId);
        broadcast.put("username",    s.username);
        broadcast(canvasId, conn, broadcast);
    }

    private void handleDeleteElement(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId  = msg.optString("canvasId");
        if (!requireCanvasMember(conn, s, canvasId)) return;
        String elementId = msg.optString("elementId");
        mongo.removeElementFromCanvas(canvasId, elementId);

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",      "ELEMENT_DELETED");
        broadcast.put("canvasId",  canvasId);
        broadcast.put("elementId", elementId);
        broadcast.put("deletedBy", s.userId);
        broadcast(canvasId, conn, broadcast);
    }

    private void handleUpdateElement(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId  = msg.optString("canvasId");
        if (!requireCanvasMember(conn, s, canvasId)) return;
        String elementId = msg.optString("elementId");
        JSONObject data  = msg.optJSONObject("data");
        if (data == null) data = new JSONObject();

        mongo.updateElementInCanvas(canvasId, elementId, Document.parse(data.toString()));

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",      "ELEMENT_UPDATED");
        broadcast.put("canvasId",  canvasId);
        broadcast.put("elementId", elementId);
        broadcast.put("data",      data);
        broadcast.put("updatedBy", s.userId);
        broadcast(canvasId, conn, broadcast);
    }

    private void handleCursorMove(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null || s.activeCanvasId == null) return;

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",     "CURSOR_MOVED");
        broadcast.put("userId",   s.userId);
        broadcast.put("username", s.username);
        broadcast.put("x",        msg.optDouble("x", 0));
        broadcast.put("y",        msg.optDouble("y", 0));
        broadcast(s.activeCanvasId, conn, broadcast);
    }

    private void joinRoom(WebSocket conn, Session s, String canvasId) {
        rooms.computeIfAbsent(canvasId, k -> ConcurrentHashMap.newKeySet()).add(conn);
        s.activeCanvasId = canvasId;
    }

    private void leaveRoom(WebSocket conn, Session s, String canvasId, boolean disconnect) {
        Set<WebSocket> room = rooms.get(canvasId);
        if (room != null) {
            room.remove(conn);
            if (room.isEmpty()) rooms.remove(canvasId);
        }
        if (!disconnect) s.activeCanvasId = null;
        if (s.userId != null) {
            broadcast(canvasId, conn, userEvent("USER_LEFT", s));
        }
    }

    private Session requireAuth(WebSocket conn, JSONObject msg) {
        Session s = sessions.get(conn);

        String token = msg.optString("token", null);
        if (token != null && (s == null || s.userId == null)) {
            User user = auth.getUserByToken(token);
            if (user != null) {
                if (s == null) { s = new Session(); sessions.put(conn, s); }
                s.token    = token;
                s.userId   = user.getId().toHexString();
                s.username = user.getUsername();
            }
        }

        if (s == null || s.userId == null) {
            send(conn, error("Not authenticated"));
            return null;
        }
        return s;
    }

    private boolean requireCanvasMember(WebSocket conn, Session session, String canvasId) {
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null) {
            send(conn, error("Canvas not found"));
            return false;
        }
        if (canvas.getMemberIds() == null || !canvas.getMemberIds().contains(session.userId)) {
            send(conn, error("Access denied: you are not a member of this canvas"));
            return false;
        }
        return true;
    }

    private void send(WebSocket conn, JSONObject msg) {
        if (conn.isOpen()) conn.send(msg.toString());
    }

    private void broadcast(String canvasId, WebSocket exclude, JSONObject msg) {
        Set<WebSocket> room = rooms.get(canvasId);
        if (room == null) return;
        String payload = msg.toString();
        for (WebSocket ws : room) {
            if (ws != exclude && ws.isOpen()) ws.send(payload);
        }
    }

    private JSONObject error(String message) {
        JSONObject j = new JSONObject();
        j.put("type",    "ERROR");
        j.put("message", message);
        return j;
    }

    private JSONObject userEvent(String type, Session s) {
        JSONObject j = new JSONObject();
        j.put("type",     type);
        j.put("userId",   s.userId);
        j.put("username", s.username);
        return j;
    }

    private JSONObject canvasToJson(CanvasDocument c) {
        JSONObject j = new JSONObject();
        j.put("id",           c.getId().toHexString());
        j.put("name",         c.getName());
        j.put("ownerId",      c.getOwnerId());
        j.put("createdAt",    c.getCreatedAt());
        j.put("updatedAt",    c.getUpdatedAt());
        j.put("canvasWidth",  c.getCanvasWidth());
        j.put("canvasHeight", c.getCanvasHeight());
        j.put("memberCount",  c.getMemberIds() != null ? c.getMemberIds().size() : 0);
        return j;
    }

    private JSONObject elementToJson(CanvasElement el) {
        JSONObject j = new JSONObject();
        j.put("elementId",   el.getElementId());
        j.put("elementType", el.getType());
        j.put("addedBy",     el.getAddedBy());
        if (el.getData() != null) {
            j.put("data", new JSONObject(el.getData().toJson()));
        }
        return j;
    }

    private static class Session {
        String token;
        String userId;
        String username;
        String activeCanvasId;
    }
}
