package org.example.canvasproject.service;

import org.example.canvasproject.models.User;
import at.favre.lib.crypto.bcrypt.BCrypt;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthService {

    private static AuthService instance;
    private final MongoDBService mongo;

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    private AuthService() {
        this.mongo = MongoDBService.getInstance();
    }

    public static synchronized AuthService getInstance() {
        if (instance == null) instance = new AuthService();
        return instance;
    }

    public User register(String username, String email, String password) {
        if (username == null || username.isBlank()) return null;
        if (password == null || password.length() < 6)  return null;
        if (mongo.findUserByUsername(username) != null) return null;

        String hash = BCrypt.withDefaults().hashToString(12, password.toCharArray());
        try {
            return mongo.createUser(username, email, hash);
        } catch (Exception e) {
            return null;
        }
    }

    public String login(String username, String password) {
        User user = mongo.findUserByUsername(username);
        if (user == null) return null;

        BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), user.getPasswordHash());
        if (!result.verified) return null;

        String token = UUID.randomUUID().toString();
        sessions.put(token, user.getId().toHexString());
        return token;
    }

    public String getUserIdByToken(String token) {
        return sessions.get(token);
    }

    public User getUserByToken(String token) {
        String userId = sessions.get(token);
        if (userId == null) return null;
        return mongo.findUserById(userId);
    }

    public void logout(String token) {
        sessions.remove(token);
    }
}
