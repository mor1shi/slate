package org.example.canvasproject.collab;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Глобальное состояние текущей сессии
 * Хранит токен, userId, username, активный canvasId
 */
public class SessionManager {

    private static SessionManager instance;

    private CollaborationClient client;
    private String token;
    private String userId;
    private String username;
    private String color = "";
    private String avatar = "";
    private String activeCanvasId;
    private String activeCanvasName;
    private String activeCanvasRole = "editor";

    private final List<Consumer<JSONObject>> messageListeners = new CopyOnWriteArrayList<>();
    private volatile Runnable connectionLostListener;

    private SessionManager() {}

    public static synchronized SessionManager getInstance() {
        if (instance == null) instance = new SessionManager();
        return instance;
    }


    public CollaborationClient getClient() {
        return client;
    }

    public void setClient(CollaborationClient client) {
        this.client = client;
    }

    /** Единая точка доставки сообщений сервера: переживает пересоздание сокета. */
    public void dispatch(JSONObject message) {
        for (Consumer<JSONObject> listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(SessionManager.class.getName())
                        .warning("Message listener failed: " + e.getMessage());
            }
        }
    }

    public void addMessageListener(Consumer<JSONObject> listener) {
        if (listener != null) messageListeners.add(listener);
    }

    public void removeMessageListener(Consumer<JSONObject> listener) {
        messageListeners.remove(listener);
    }

    public void setConnectionLostListener(Runnable listener) {
        this.connectionLostListener = listener;
    }

    public void fireConnectionLost() {
        Runnable listener = connectionLostListener;
        if (listener != null) listener.run();
    }

    public boolean isConnected() {
        return client != null && client.isOpen();
    }


    public void onLoginSuccess(String token, String userId, String username) {
        onLoginSuccess(token, userId, username, "", "");
    }

    public void onLoginSuccess(String token, String userId, String username, String color, String avatar) {
        this.token    = token;
        this.userId   = userId;
        this.username = username;
        this.color    = color == null ? "" : color;
        this.avatar   = avatar == null ? "" : avatar;
        if (client != null) client.setToken(token);
    }

    public void onLogout() {
        this.token    = null;
        this.userId   = null;
        this.username = null;
        this.activeCanvasId   = null;
        this.activeCanvasName = null;
    }

    public boolean isLoggedIn() {
        return token != null && userId != null;
    }

    public String getToken()    { return token; }
    public String getUserId()   { return userId; }
    public String getUsername() { return username; }
    public String getColor()    { return color; }
    public String getAvatar()   { return avatar; }

    public void setProfile(String username, String color, String avatar) {
        this.username = username;
        this.color    = color == null ? "" : color;
        this.avatar   = avatar == null ? "" : avatar;
    }


    public void setActiveCanvas(String canvasId, String canvasName) {
        setActiveCanvas(canvasId, canvasName, "editor");
    }

    public void setActiveCanvas(String canvasId, String canvasName, String role) {
        this.activeCanvasId   = canvasId;
        this.activeCanvasName = canvasName;
        this.activeCanvasRole = (role == null || role.isBlank()) ? "editor" : role;
    }

    public String getActiveCanvasId()   { return activeCanvasId; }
    public String getActiveCanvasName() { return activeCanvasName; }
    public String getActiveCanvasRole() { return activeCanvasRole; }

    public boolean isViewer() {
        return "viewer".equals(activeCanvasRole);
    }

    public boolean isOwner() {
        return "owner".equals(activeCanvasRole);
    }
}
