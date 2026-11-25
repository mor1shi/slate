package org.example.canvasproject.server;

import org.bson.Document;
import org.example.canvasproject.models.*;
import org.example.canvasproject.service.*;
import org.example.canvasproject.storage.AssetStore;
import org.example.canvasproject.storage.ElementUpdate;
import org.example.canvasproject.storage.FileAssetStore;
import org.example.canvasproject.service.repository.MongoStorage;
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
 *   AUTH_DEVICE      deviceKey, deviceName   (основной режим)
 *   REGISTER         username, email, password (совместимость)
 *   LOGIN            username, password        (совместимость)
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
    private final AssetStore assets = new FileAssetStore(ServerConfig.assetsDir());
    /** Репозиторий элементов: пакетные операции атомарны. */
    private final MongoStorage storage = new MongoStorage(mongo, ServerConfig.assetsDir());

    private final boolean allowNewDevices = ServerConfig.allowNewDevices();
    private final int maxMessagesPer10s  = ServerConfig.maxMessagesPer10s();
    private final int maxConnectionsPerIp = ServerConfig.maxConnectionsPerIp();
    private final long authDeadlineMs    = ServerConfig.authTimeoutSeconds() * 1000L;

    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocket>> rooms = new ConcurrentHashMap<>();
    private final Map<String, Integer> connectionsPerIp = new ConcurrentHashMap<>();

    public CollaborationServer(int port) {
        this(new InetSocketAddress(port));
    }

    public CollaborationServer(InetSocketAddress address) {
        super(address);
        setReuseAddr(true);
        setConnectionLostTimeout(60);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String ip = remoteIp(conn);
        int count = connectionsPerIp.merge(ip, 1, Integer::sum);
        if (count > maxConnectionsPerIp) {
            log.warning("Too many connections from " + ip + " (" + count + ")");
            connectionsPerIp.merge(ip, -1, Integer::sum);
            conn.close(1008, "Too many connections");
            return;
        }
        Session s = new Session();
        s.openedAt = System.currentTimeMillis();
        sessions.put(conn, s);
        log.info("New connection: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = sessions.remove(conn);
        connectionsPerIp.merge(remoteIp(conn), -1, Integer::sum);
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
            if (message.length() > 8_000_000) {
                conn.close(1009, "Message too big");
                return;
            }

            Session s = sessions.get(conn);
            if (s == null) return;

            long now = System.currentTimeMillis();
            if (s.userId == null && now - s.openedAt > authDeadlineMs) {
                send(conn, error("Authentication timeout"));
                conn.close(1008, "Authentication timeout");
                return;
            }
            if (!allowMessage(s, now)) {
                send(conn, error("Too many messages, slow down"));
                return;
            }

            JSONObject msg = new JSONObject(message);
            String type = msg.optString("type", "");

            if (s.userId == null
                    && !"AUTH_DEVICE".equals(type)
                    && !"LOGIN".equals(type)
                    && !"REGISTER".equals(type)) {
                send(conn, error("Not authenticated"));
                return;
            }

            switch (type) {
                case "AUTH_DEVICE"    -> handleAuthDevice(conn, msg);
                case "REGISTER"       -> handleRegister(conn, msg);
                case "LOGIN"          -> handleLogin(conn, msg);
                case "LOGOUT"         -> handleLogout(conn, msg);
                case "UPDATE_PROFILE" -> handleUpdateProfile(conn, msg);
                case "CREATE_CANVAS"  -> handleCreateCanvas(conn, msg);
                case "LIST_CANVASES"  -> handleListCanvases(conn, msg);
                case "JOIN_CANVAS"    -> handleJoinCanvas(conn, msg);
                case "JOIN_BY_CODE"   -> handleJoinByCode(conn, msg);
                case "LEAVE_CANVAS"   -> handleLeaveCanvas(conn, msg);
                case "RENAME_CANVAS"  -> handleRenameCanvas(conn, msg);
                case "RESIZE_CANVAS"  -> handleResizeCanvas(conn, msg);
                case "DELETE_CANVAS"  -> handleDeleteCanvas(conn, msg);
                case "GEN_INVITE"     -> handleGenInvite(conn, msg);
                case "REVOKE_INVITE"  -> handleRevokeInvite(conn, msg);
                case "ADD_ELEMENT"    -> handleAddElement(conn, msg);
                case "DELETE_ELEMENT" -> handleDeleteElement(conn, msg);
                case "UPDATE_ELEMENT" -> handleUpdateElement(conn, msg);
                case "UPDATE_ELEMENTS" -> handleUpdateElements(conn, msg);
                case "DELETE_ELEMENTS" -> handleDeleteElements(conn, msg);
                case "CURSOR_MOVE"    -> handleCursorMove(conn, msg);
                case "ASSET_PUT"      -> handleAssetPut(conn, msg);
                case "ASSET_GET"      -> handleAssetGet(conn, msg);
                default               -> send(conn, error("Unknown message type: " + type));
            }
        } catch (Exception e) {
            log.warning("Failed to process message: " + e.getMessage());
            send(conn, error("Server error: " + e.getMessage()));
        }
    }

    /** Простое ограничение частоты сообщений, чтобы одно соединение не забивало сервер. */
    private boolean allowMessage(Session s, long now) {
        if (now - s.windowStart >= 10_000) {
            s.windowStart = now;
            s.windowCount = 0;
        }
        return ++s.windowCount <= maxMessagesPer10s;
    }

    private String remoteIp(WebSocket conn) {
        try {
            return conn.getRemoteSocketAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void handleAuthDevice(WebSocket conn, JSONObject msg) {
        String deviceKey  = msg.optString("deviceKey", "");
        String deviceName = msg.optString("deviceName", "");

        User user = auth.loginWithDeviceKey(deviceKey, deviceName, allowNewDevices);
        if (user == null) {
            send(conn, error(allowNewDevices
                    ? "Некорректный ключ устройства"
                    : "Этот сервер не принимает новые устройства. Скопируйте ключ с уже подключённого."));
            return;
        }

        String token = auth.issueToken(user);
        Session s = sessions.get(conn);
        if (s == null) { s = new Session(); sessions.put(conn, s); }
        s.token    = token;
        s.userId   = user.getId().toHexString();
        s.username = user.getUsername();
        s.color    = user.getColor();
        s.avatar   = user.getAvatar();

        JSONObject resp = new JSONObject();
        resp.put("type",     "AUTH_OK");
        resp.put("token",    token);
        resp.put("userId",   s.userId);
        resp.put("username", s.username);
        resp.put("color",    s.color == null ? "" : s.color);
        resp.put("avatar",   s.avatar == null ? "" : s.avatar);
        send(conn, resp);
        log.info("Device authenticated: " + s.username + " (" + s.userId + ")");
    }

    private void handleRegister(WebSocket conn, JSONObject msg) {
        if (!ServerConfig.allowPasswordAuth()) {
            send(conn, error("Вход по паролю отключён. Используйте ключ устройства."));
            return;
        }
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
        if (!ServerConfig.allowPasswordAuth()) {
            send(conn, error("Вход по паролю отключён. Используйте ключ устройства."));
            return;
        }
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

    private void handleUpdateProfile(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String username = msg.optString("username", "").trim();
        String color    = msg.optString("color", "").trim();
        String avatar   = msg.optString("avatar", "");

        if (username.length() < 1 || username.length() > 32) {
            send(conn, error("Имя должно быть от 1 до 32 символов"));
            return;
        }
        if (!username.matches("[\\p{L}\\p{N} _.-]+")) {
            send(conn, error("Недопустимые символы в имени"));
            return;
        }
        if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
            send(conn, error("Некорректный цвет"));
            return;
        }
        if (!avatar.isBlank()) {
            if (!avatar.startsWith("data:image/")) {
                send(conn, error("Аватар должен быть изображением"));
                return;
            }
            if (avatar.length() > 1_500_000) {
                send(conn, error("Аватар слишком большой (макс. ~1 МБ)"));
                return;
            }
        }

        boolean ok = mongo.updateUserProfile(s.userId, username, color, avatar);
        if (!ok) {
            send(conn, error("Не удалось сохранить профиль"));
            return;
        }
        s.username = username;
        s.color    = color;
        s.avatar   = avatar;

        JSONObject resp = new JSONObject();
        resp.put("type",     "PROFILE_UPDATED");
        resp.put("username", username);
        resp.put("color",    color);
        resp.put("avatar",   avatar);
        send(conn, resp);

        // Новое имя/цвет сразу видны участникам открытой доски.
        if (s.activeCanvasId != null) {
            JSONObject ev = new JSONObject();
            ev.put("type",     "USER_UPDATED");
            ev.put("userId",   s.userId);
            ev.put("username", s.username);
            ev.put("color",    s.color);
            broadcast(s.activeCanvasId, null, ev);
        }
    }

    private void handleCreateCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String name = msg.optString("name", "Untitled Canvas");
        int w = Math.max(400, Math.min(4000,
                msg.optInt("canvasWidth", msg.optInt("width", 1920))));
        int h = Math.max(300, Math.min(4000,
                msg.optInt("canvasHeight", msg.optInt("height", 1080))));

        CanvasDocument canvas = mongo.createCanvas(name, s.userId, w, h);

        JSONObject resp = new JSONObject();
        resp.put("type",   "CANVAS_CREATED");
        resp.put("canvas", canvasToJson(canvas, s.userId));
        send(conn, resp);
    }

    private void handleListCanvases(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        List<CanvasDocument> list = mongo.getCanvasesForUser(s.userId);
        JSONArray arr = new JSONArray();
        for (CanvasDocument c : list) arr.put(canvasToJson(c, s.userId));

        JSONObject resp = new JSONObject();
        resp.put("type",     "CANVAS_LIST");
        resp.put("canvases", arr);
        send(conn, resp);
    }

    private void handleJoinCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        log.info("JOIN_CANVAS " + canvasId + " user=" + s.userId);
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null) { send(conn, error("Canvas not found")); return; }
        if (!canvas.getMemberIds().contains(s.userId)) {
            send(conn, error("Access denied: you are not a member of this canvas"));
            return;
        }

        String role = canvas.roleOf(s.userId);
        if (s.activeCanvasId != null && !s.activeCanvasId.equals(canvasId)) {
            leaveRoom(conn, s, s.activeCanvasId, false);
        }

        joinRoom(conn, s, canvasId);
        s.activeCanvasRole = role;

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
        resp.put("role",         role);
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

        String requestedId = msg.optString("canvasId");
        String leftCanvasId = s.activeCanvasId != null ? s.activeCanvasId : requestedId;
        // Выходим из фактической комнаты, а не из той, что прислал клиент:
        // иначе сокет остаётся «призраком» и продолжает получать события.
        if (s.activeCanvasId != null) {
            leaveRoom(conn, s, s.activeCanvasId, false);
        }

        JSONObject resp = new JSONObject();
        resp.put("type", "LEFT_CANVAS");
        resp.put("canvasId", leftCanvasId);
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

    private void handleResizeCanvas(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        int w = Math.max(400, Math.min(4000, msg.optInt("width", 0)));
        int h = Math.max(300, Math.min(4000, msg.optInt("height", 0)));
        if (msg.optInt("width", 0) <= 0 || msg.optInt("height", 0) <= 0) {
            send(conn, error("Некорректный размер холста"));
            return;
        }

        boolean ok = mongo.resizeCanvas(canvasId, w, h, s.userId);
        if (!ok) {
            send(conn, error("Только редактор может изменять доску"));
            return;
        }

        JSONObject resp = new JSONObject();
        resp.put("type",         "CANVAS_RESIZED");
        resp.put("canvasId",     canvasId);
        resp.put("canvasWidth",  w);
        resp.put("canvasHeight", h);
        broadcast(canvasId, null, resp);
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
        String role = CanvasDocument.ROLE_VIEWER.equals(msg.optString("role"))
                ? CanvasDocument.ROLE_VIEWER : CanvasDocument.ROLE_EDITOR;
        int hours = Math.max(1, Math.min(msg.optInt("hours", 24), 24));
        int uses  = Math.max(1, Math.min(msg.optInt("maxUses", 1), 50));

        InviteCode code = invite.generateCode(canvasId, s.userId, role,
                hours * 60L * 60L * 1000L, uses);
        if (code == null) {
            send(conn, error("Недостаточно прав для приглашения"));
            return;
        }

        JSONObject resp = new JSONObject();
        resp.put("type",      "INVITE_CODE");
        resp.put("code",      code.getCode());
        resp.put("role",      code.getRole());
        resp.put("maxUses",   code.getMaxUses());
        resp.put("expiresAt", code.getExpiresAt());
        send(conn, resp);
    }

    private void handleRevokeInvite(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        String code     = msg.optString("code", "").toUpperCase(Locale.ROOT).trim();
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null || !canvas.canEdit(s.userId)) {
            send(conn, error("Недостаточно прав"));
            return;
        }
        mongo.revokeInvite(code, canvasId);

        JSONObject resp = new JSONObject();
        resp.put("type",     "INVITE_REVOKED");
        resp.put("code",     code);
        resp.put("canvasId", canvasId);
        send(conn, resp);
    }

    private void handleAssetPut(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String assetId = msg.optString("assetId", "");
        String payload = msg.optString("data", "");
        if (!assetId.matches("[0-9a-f]{64}") || payload.isEmpty()) {
            send(conn, error("Некорректный ассет"));
            return;
        }
        // Лимит base64 ~ 4/3 от бинарного размера.
        if (payload.length() > 23_000_000) {
            send(conn, error("Ассет слишком большой"));
            return;
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(payload);
            String actual = FileAssetStore.hash(bytes);
            if (!actual.equals(assetId)) {
                send(conn, error("Хеш ассета не совпадает"));
                return;
            }
            assets.put(bytes);
            send(conn, new JSONObject().put("type", "ASSET_STORED").put("assetId", assetId));
        } catch (IllegalArgumentException e) {
            send(conn, error("Ассет отклонён: " + e.getMessage()));
        }
    }

    private void handleAssetGet(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String assetId = msg.optString("assetId", "");
        if (!assetId.matches("[0-9a-f]{64}")) {
            send(conn, error("Некорректный ассет"));
            return;
        }
        java.util.Optional<byte[]> bytes = assets.get(assetId);
        if (bytes.isEmpty()) {
            send(conn, error("Файл не найден: " + assetId.substring(0, 8)));
            return;
        }
        JSONObject resp = new JSONObject();
        resp.put("type",    "ASSET_DATA");
        resp.put("assetId", assetId);
        resp.put("data",    java.util.Base64.getEncoder().encodeToString(bytes.get()));
        send(conn, resp);
    }

    private void handleAddElement(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId    = msg.optString("canvasId");
        String elementId   = msg.optString("elementId");
        String elementType = msg.optString("elementType");
        JSONObject data    = msg.optJSONObject("data");
        if (data == null) data = new JSONObject();

        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null || !canvas.canEdit(s.userId)) {
            send(conn, error("Только редактор может изменять доску"));
            return;
        }
        if (elementId.isBlank() || elementId.length() > 128 || elementType.length() > 32) {
            send(conn, error("Некорректный элемент"));
            return;
        }
        if (data.toString().length() > 6_000_000) {
            send(conn, error("Элемент слишком большой"));
            return;
        }

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
        String elementId = msg.optString("elementId");

        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null || !canvas.canEdit(s.userId)) {
            send(conn, error("Только редактор может изменять доску"));
            return;
        }
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
        String elementId = msg.optString("elementId");
        if (!msg.has("data") || msg.isNull("data")) {
            send(conn, error("Поле data обязательно"));
            return;
        }
        JSONObject data  = msg.optJSONObject("data");
        if (data == null) data = new JSONObject();

        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null || !canvas.canEdit(s.userId)) {
            send(conn, error("Только редактор может изменять доску"));
            return;
        }
        if (data.toString().length() > 6_000_000) {
            send(conn, error("Элемент слишком большой"));
            return;
        }
        mongo.updateElementInCanvas(canvasId, elementId, Document.parse(data.toString()));

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",      "ELEMENT_UPDATED");
        broadcast.put("canvasId",  canvasId);
        broadcast.put("elementId", elementId);
        broadcast.put("data",      data);
        broadcast.put("updatedBy", s.userId);
        broadcast(canvasId, conn, broadcast);
    }

    /** Пакетное обновление группы: проверяем весь список до записи. */
    private void handleUpdateElements(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null || !canvas.canEdit(s.userId)) {
            send(conn, error("Только редактор может изменять доску"));
            return;
        }

        JSONArray input = msg.optJSONArray("updates");
        if (input == null || input.isEmpty()) {
            send(conn, error("Список updates пуст"));
            return;
        }
        if (input.length() > 10_000) {
            send(conn, error("Слишком много элементов в пакете"));
            return;
        }

        List<ElementUpdate> updates = new ArrayList<>(input.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item == null) {
                send(conn, error("Некорректное обновление в пакете"));
                return;
            }
            String elementId = item.optString("elementId", "");
            if (elementId.isBlank() || elementId.length() > 128 || !seen.add(elementId)) {
                send(conn, error("Некорректный elementId в пакете"));
                return;
            }
            JSONObject data = item.optJSONObject("data");
            if (data == null) {
                send(conn, error("Поле data обязательно"));
                return;
            }
            if (data.toString().length() > 6_000_000) {
                send(conn, error("Элемент слишком большой"));
                return;
            }
            updates.add(new ElementUpdate(elementId, data));
        }

        storage.elements().updateMany(canvasId, updates);

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",      "ELEMENTS_UPDATED");
        broadcast.put("canvasId",  canvasId);
        broadcast.put("updates",   input);
        broadcast.put("updatedBy", s.userId);
        broadcast(canvasId, conn, broadcast);
    }

    /** Пакетное удаление группы одной транзакцией. */
    private void handleDeleteElements(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        if (s == null) return;

        String canvasId = msg.optString("canvasId");
        CanvasDocument canvas = mongo.findCanvasById(canvasId);
        if (canvas == null || !canvas.canEdit(s.userId)) {
            send(conn, error("Только редактор может изменять доску"));
            return;
        }

        JSONArray input = msg.optJSONArray("elementIds");
        if (input == null || input.isEmpty()) {
            send(conn, error("Список elementIds пуст"));
            return;
        }
        if (input.length() > 10_000) {
            send(conn, error("Слишком много элементов в пакете"));
            return;
        }

        List<String> ids = new ArrayList<>(input.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            String id = input.optString(i, "");
            if (id.isBlank() || id.length() > 128 || !seen.add(id)) {
                send(conn, error("Некорректный elementId в пакете"));
                return;
            }
            ids.add(id);
        }

        storage.elements().deleteMany(canvasId, ids);

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",       "ELEMENTS_DELETED");
        broadcast.put("canvasId",   canvasId);
        broadcast.put("elementIds", input);
        broadcast.put("deletedBy",  s.userId);
        broadcast(canvasId, conn, broadcast);
    }

    private void handleCursorMove(WebSocket conn, JSONObject msg) {
        Session s = requireAuth(conn, msg);
        // Роль закэширована при входе в комнату: не читаем всю доску на каждое
        // движение мыши.
        if (s == null || s.activeCanvasId == null || s.activeCanvasRole == null) return;

        JSONObject broadcast = new JSONObject();
        broadcast.put("type",     "CURSOR_MOVED");
        broadcast.put("userId",   s.userId);
        broadcast.put("username", s.username);
        broadcast.put("color",    s.color == null ? "" : s.color);
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
        if (!disconnect) s.activeCanvasRole = null;
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
        j.put("color",    s.color == null ? "" : s.color);
        return j;
    }

    private JSONObject canvasToJson(CanvasDocument c, String forUserId) {
        JSONObject j = new JSONObject();
        j.put("id",           c.getId().toHexString());
        j.put("name",         c.getName());
        j.put("ownerId",      c.getOwnerId());
        j.put("role",         c.roleOf(forUserId));
        j.put("createdAt",    c.getCreatedAt());
        j.put("updatedAt",    c.getUpdatedAt());
        j.put("canvasWidth",  c.getCanvasWidth());
        j.put("canvasHeight", c.getCanvasHeight());
        j.put("width",        c.getCanvasWidth());
        j.put("height",       c.getCanvasHeight());
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
        String color;
        String avatar;
        String activeCanvasId;
        String activeCanvasRole;
        long openedAt;
        long windowStart;
        int windowCount;
    }
}
