package org.example.canvasproject.collab;

import javafx.application.Platform;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * WebSocket клиент
 *   localhost:8765
 */
public class CollaborationClient extends WebSocketClient {

    private static final Logger log = Logger.getLogger(CollaborationClient.class.getName());

    private Consumer<JSONObject> messageHandler;
    private Runnable onConnected;
    private Consumer<String> onDisconnected;

    private String savedToken;

    public CollaborationClient(String serverUrl) throws Exception {
        super(new URI(serverUrl));
    }


    public void setMessageHandler(Consumer<JSONObject> handler) {
        this.messageHandler = handler;
    }

    public Consumer<JSONObject> getMessageHandler() {
        return messageHandler;
    }

    public void setOnConnected(Runnable onConnected) {
        this.onConnected = onConnected;
    }

    public void setOnDisconnected(Consumer<String> onDisconnected) {
        this.onDisconnected = onDisconnected;
    }


    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("Connected to collaboration server");
        if (onConnected != null) {
            Platform.runLater(onConnected);
        }
    }

    @Override
    public void onMessage(String message) {
        if (messageHandler == null) return;
        try {
            JSONObject msg = new JSONObject(message);
            Platform.runLater(() -> messageHandler.accept(msg));
        } catch (Exception e) {
            log.warning("Failed to parse message: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("Disconnected from server. Reason: " + reason);
        if (onDisconnected != null) {
            Platform.runLater(() -> onDisconnected.accept(reason));
        }
    }

    @Override
    public void onError(Exception e) {
        log.warning("WebSocket error: " + e.getMessage());
        if (onDisconnected != null) {
            String reason = e.getMessage() == null ? "ошибка соединения" : e.getMessage();
            Platform.runLater(() -> onDisconnected.accept(reason));
        }
    }


    public void sendMessage(JSONObject msg) {
        if (isOpen()) {
            send(msg.toString());
        } else {
            log.warning("Tried to send message while disconnected: " + msg.optString("type"));
        }
    }

    public void register(String username, String email, String password) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "REGISTER");
        msg.put("username", username);
        msg.put("email",    email);
        msg.put("password", password);
        sendMessage(msg);
    }

    public void login(String username, String password) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "LOGIN");
        msg.put("username", username);
        msg.put("password", password);
        sendMessage(msg);
    }

    public void authDevice(String deviceKey, String deviceName) {
        JSONObject msg = new JSONObject();
        msg.put("type",       "AUTH_DEVICE");
        msg.put("deviceKey",  deviceKey);
        msg.put("deviceName", deviceName);
        sendMessage(msg);
    }

    public void logout() {
        JSONObject msg = new JSONObject();
        msg.put("type",  "LOGOUT");
        msg.put("token", savedToken);
        sendMessage(msg);
        savedToken = null;
    }

    public void createCanvas(String name) {
        createCanvas(name, 1920, 1080);
    }

    public void createCanvas(String name, int width, int height) {
        JSONObject msg = new JSONObject();
        msg.put("type",   "CREATE_CANVAS");
        msg.put("token",  savedToken);
        msg.put("name",   name);
        msg.put("width",  Math.max(400, Math.min(4000, width)));
        msg.put("height", Math.max(300, Math.min(4000, height)));
        sendMessage(msg);
    }

    public void renameCanvas(String canvasId, String newName) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "RENAME_CANVAS");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        msg.put("name",     newName);
        sendMessage(msg);
    }

    public void resizeCanvas(String canvasId, int width, int height) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "RESIZE_CANVAS");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        msg.put("width",    width);
        msg.put("height",   height);
        sendMessage(msg);
    }

    public void deleteCanvas(String canvasId) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "DELETE_CANVAS");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        sendMessage(msg);
    }

    public void listCanvases() {
        JSONObject msg = new JSONObject();
        msg.put("type",  "LIST_CANVASES");
        msg.put("token", savedToken);
        sendMessage(msg);
    }

    public void joinCanvas(String canvasId) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "JOIN_CANVAS");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        sendMessage(msg);
    }

    public void joinByCode(String code) {
        JSONObject msg = new JSONObject();
        msg.put("type",  "JOIN_BY_CODE");
        msg.put("token", savedToken);
        msg.put("code",  code);
        sendMessage(msg);
    }

    public void leaveCanvas(String canvasId) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "LEAVE_CANVAS");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        sendMessage(msg);
    }

    public void generateInviteCode(String canvasId) {
        generateInviteCode(canvasId, "viewer", 24, 1);
    }

    public void generateInviteCode(String canvasId, String role, int hours, int maxUses) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "GEN_INVITE");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        msg.put("role",     role);
        msg.put("hours",    hours);
        msg.put("maxUses",  maxUses);
        sendMessage(msg);
    }

    public void updateProfile(String username, String color, String avatar) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "UPDATE_PROFILE");
        msg.put("token",    savedToken);
        msg.put("username", username);
        msg.put("color",    color);
        msg.put("avatar",   avatar == null ? "" : avatar);
        sendMessage(msg);
    }

    public void revokeInvite(String canvasId, String code) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "REVOKE_INVITE");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        msg.put("code",     code);
        sendMessage(msg);
    }

    public void addElement(String canvasId, String elementId, String elementType, JSONObject data) {
        JSONObject msg = new JSONObject();
        msg.put("type",        "ADD_ELEMENT");
        msg.put("token",       savedToken);
        msg.put("canvasId",    canvasId);
        msg.put("elementId",   elementId);
        msg.put("elementType", elementType);
        msg.put("data",        data);
        sendMessage(msg);
    }

    public void deleteElement(String canvasId, String elementId) {
        JSONObject msg = new JSONObject();
        msg.put("type",      "DELETE_ELEMENT");
        msg.put("token",     savedToken);
        msg.put("canvasId",  canvasId);
        msg.put("elementId", elementId);
        sendMessage(msg);
    }

    public void updateElement(String canvasId, String elementId, JSONObject data) {
        JSONObject msg = new JSONObject();
        msg.put("type",      "UPDATE_ELEMENT");
        msg.put("token",     savedToken);
        msg.put("canvasId",  canvasId);
        msg.put("elementId", elementId);
        msg.put("data",      data);
        sendMessage(msg);
    }

    /** Пакетное обновление группы: сервер применяет его одной транзакцией. */
    public void updateElements(String canvasId, org.json.JSONArray updates) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "UPDATE_ELEMENTS");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        msg.put("updates",  updates);
        sendMessage(msg);
    }

    /** Пакетное удаление группы: сервер применяет его одной транзакцией. */
    public void deleteElements(String canvasId, org.json.JSONArray elementIds) {
        JSONObject msg = new JSONObject();
        msg.put("type",      "DELETE_ELEMENTS");
        msg.put("token",     savedToken);
        msg.put("canvasId",  canvasId);
        msg.put("elementIds", elementIds);
        sendMessage(msg);
    }

    /** Заранее выгружает картинку на сервер: в элементе останется только хеш. */
    public void putAsset(String assetId, String mime, byte[] bytes) {
        JSONObject msg = new JSONObject();
        msg.put("type",    "ASSET_PUT");
        msg.put("token",   savedToken);
        msg.put("assetId", assetId);
        msg.put("mime",    mime == null ? "image/png" : mime);
        msg.put("data",    java.util.Base64.getEncoder().encodeToString(bytes));
        sendMessage(msg);
    }

    public void getAsset(String assetId) {
        JSONObject msg = new JSONObject();
        msg.put("type",    "ASSET_GET");
        msg.put("token",   savedToken);
        msg.put("assetId", assetId);
        sendMessage(msg);
    }

    public void sendCursorMove(String canvasId, double x, double y) {
        JSONObject msg = new JSONObject();
        msg.put("type",     "CURSOR_MOVE");
        msg.put("token",    savedToken);
        msg.put("canvasId", canvasId);
        msg.put("x", x);
        msg.put("y", y);
        sendMessage(msg);
    }


    public void setToken(String token) {
        this.savedToken = token;
    }

    public String getToken() {
        return savedToken;
    }

    public boolean isAuthenticated() {
        return savedToken != null;
    }
}
