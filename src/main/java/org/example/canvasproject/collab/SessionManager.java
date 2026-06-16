package org.example.canvasproject.collab;

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
    private String activeCanvasId;
    private String activeCanvasName;

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

    public boolean isConnected() {
        return client != null && client.isOpen();
    }


    public void onLoginSuccess(String token, String userId, String username) {
        this.token    = token;
        this.userId   = userId;
        this.username = username;
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


    public void setActiveCanvas(String canvasId, String canvasName) {
        this.activeCanvasId   = canvasId;
        this.activeCanvasName = canvasName;
    }

    public String getActiveCanvasId()   { return activeCanvasId; }
    public String getActiveCanvasName() { return activeCanvasName; }
}
