package org.example.canvasproject.service;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.example.canvasproject.models.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

public class MongoDBService {

    private static MongoDBService instance;
    private static final long TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private final MongoClient mongoClient;
    private final MongoDatabase db;

    private final MongoCollection<Document> users;
    private final MongoCollection<Document> canvases;
    private final MongoCollection<Document> inviteCodes;
    private final MongoCollection<Document> tokens;

    private MongoDBService(String connectionString, String dbName) {
        mongoClient = MongoClients.create(connectionString);
        db = mongoClient.getDatabase(dbName);
        users       = db.getCollection("users");
        canvases    = db.getCollection("canvases");
        inviteCodes = db.getCollection("invite_codes");
        tokens      = db.getCollection("device_tokens");

        // Имена устройств могут совпадать; уникален только ключ устройства.
        try { users.dropIndex("username_1"); } catch (Exception ignored) {}
        users.createIndex(Indexes.ascending("username"));
        users.createIndex(Indexes.ascending("deviceKeyHash"),
                new IndexOptions().unique(true).sparse(true));

        try { users.dropIndex("email_1"); } catch (Exception ignored) {}
        try { users.updateMany(eq("email", ""), unset("email")); } catch (Exception ignored) {}
        users.createIndex(Indexes.ascending("email"), new IndexOptions().unique(true).sparse(true));

        tokens.createIndex(Indexes.ascending("token"), new IndexOptions().unique(true));
        tokens.createIndex(Indexes.ascending("userId"));
        tokens.createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));

        canvases.createIndex(Indexes.ascending("memberIds"));
        canvases.createIndex(Indexes.ascending("ownerId"));

        inviteCodes.createIndex(Indexes.ascending("code"), new IndexOptions().unique(true));
        inviteCodes.createIndex(
            Indexes.ascending("expiresAt"),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)
        );
    }

    public static synchronized MongoDBService getInstance() {
        if (instance == null) {
            String uri    = System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
            String dbName = System.getenv().getOrDefault("MONGO_DB",  "canvas_collab");
            instance = new MongoDBService(uri, dbName);
        }
        return instance;
    }

    public User createUser(String username, String email, String passwordHash) {
        Document doc = new Document()
            .append("username",     username)
            .append("passwordHash", passwordHash)
            .append("createdAt",    System.currentTimeMillis());
        String trimmedEmail = (email != null) ? email.trim() : "";
        if (!trimmedEmail.isEmpty()) {
            doc.append("email", trimmedEmail);
        }
        users.insertOne(doc);
        User u = new User(username, trimmedEmail.isEmpty() ? null : trimmedEmail, passwordHash);
        u.setId(doc.getObjectId("_id"));
        return u;
    }

    public User findUserByDeviceKey(String deviceKeyHash) {
        Document doc = users.find(eq("deviceKeyHash", deviceKeyHash)).first();
        return doc != null ? docToUser(doc) : null;
    }

    public User createDeviceUser(String deviceKeyHash, String displayName) {
        String color = defaultColor(deviceKeyHash);
        Document doc = new Document()
            .append("username",     displayName)
            .append("deviceKeyHash", deviceKeyHash)
            .append("avatar",       "")
            .append("color",        color)
            .append("createdAt",    System.currentTimeMillis());
        users.insertOne(doc);
        User u = new User(displayName, null, null);
        u.setId(doc.getObjectId("_id"));
        u.setColor(color);
        u.setAvatar("");
        u.setCreatedAt(doc.getLong("createdAt"));
        return u;
    }

    /** Стабильный цвет по ключу устройства: у пользователя он не меняется сам по себе. */
    private String defaultColor(String deviceKeyHash) {
        String[] palette = {
            "#FF3B30", "#FF9500", "#34C759", "#5AC8FA",
            "#AF52DE", "#FF2D55", "#00C7BE", "#FFD60A"
        };
        int idx = Math.floorMod(deviceKeyHash.hashCode(), palette.length);
        return palette[idx];
    }

    public boolean updateUserProfile(String userId, String username, String color, String avatar) {
        try {
            Document set = new Document("username", username)
                    .append("color", color)
                    .append("avatar", avatar == null ? "" : avatar);
            return users.updateOne(eq("_id", new ObjectId(userId)),
                    new Document("$set", set)).getMatchedCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void saveToken(String token, String userId) {
        long now = System.currentTimeMillis();
        tokens.replaceOne(
            eq("token", token),
            new Document("token", token)
                .append("userId", userId)
                .append("createdAt", now)
                .append("expiresAt", new java.util.Date(now + TOKEN_TTL_MS)),
            new ReplaceOptions().upsert(true)
        );
    }

    public String findUserIdByToken(String token) {
        if (token == null || token.isBlank()) return null;
        Document doc = tokens.find(and(
            eq("token", token),
            or(gt("expiresAt", new java.util.Date()), exists("expiresAt", false))
        )).first();
        return doc != null ? doc.getString("userId") : null;
    }

    public void deleteToken(String token) {
        if (token != null) tokens.deleteOne(eq("token", token));
    }

    public User findUserByUsername(String username) {
        Document doc = users.find(eq("username", username)).first();
        return doc != null ? docToUser(doc) : null;
    }

    public User findUserById(String userId) {
        Document doc = users.find(eq("_id", new ObjectId(userId))).first();
        return doc != null ? docToUser(doc) : null;
    }

    private User docToUser(Document doc) {
        User u = new User();
        u.setId(doc.getObjectId("_id"));
        u.setUsername(doc.getString("username"));
        u.setEmail(doc.getString("email"));
        u.setPasswordHash(doc.getString("passwordHash"));
        u.setAvatar(doc.getString("avatar"));
        u.setColor(doc.getString("color"));
        Long created = doc.getLong("createdAt");
        u.setCreatedAt(created != null ? created : 0L);
        return u;
    }

    public CanvasDocument createCanvas(String name, String ownerId, int canvasWidth, int canvasHeight) {
        Document doc = new Document()
            .append("name",         name)
            .append("ownerId",      ownerId)
            .append("memberIds",    List.of(ownerId))
            .append("memberRoles",  new Document(ownerId, CanvasDocument.ROLE_OWNER))
            .append("elements",     new ArrayList<>())
            .append("canvasWidth",  canvasWidth)
            .append("canvasHeight", canvasHeight)
            .append("createdAt",    System.currentTimeMillis())
            .append("updatedAt",    System.currentTimeMillis());
        canvases.insertOne(doc);
        CanvasDocument c = new CanvasDocument(name, ownerId);
        c.setId(doc.getObjectId("_id"));
        c.setCanvasWidth(canvasWidth);
        c.setCanvasHeight(canvasHeight);
        return c;
    }

    public CanvasDocument createCanvas(String name, String ownerId) {
        return createCanvas(name, ownerId, 1920, 1080);
    }

    public List<CanvasDocument> getCanvasesForUser(String userId) {
        List<CanvasDocument> result = new ArrayList<>();
        canvases.find(in("memberIds", userId)).forEach(doc -> result.add(docToCanvas(doc)));
        return result;
    }

    public CanvasDocument findCanvasById(String canvasId) {
        try {
            Document doc = canvases.find(eq("_id", new ObjectId(canvasId))).first();
            return doc != null ? docToCanvas(doc) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Меняет размер листа. Только владелец и редактор. */
    public boolean resizeCanvas(String canvasId, int width, int height, String requestingUserId) {
        try {
            CanvasDocument canvas = findCanvasById(canvasId);
            if (canvas == null || !canvas.canEdit(requestingUserId)) return false;

            canvases.updateOne(
                eq("_id", new ObjectId(canvasId)),
                combine(
                    set("canvasWidth", width),
                    set("canvasHeight", height),
                    set("updatedAt", System.currentTimeMillis())
                )
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean renameCanvas(String canvasId, String newName, String requestingUserId) {
        try {
            CanvasDocument canvas = findCanvasById(canvasId);
            if (canvas == null) return false;
            if (!canvas.getOwnerId().equals(requestingUserId)) return false;

            canvases.updateOne(
                eq("_id", new ObjectId(canvasId)),
                combine(
                    set("name", newName),
                    set("updatedAt", System.currentTimeMillis())
                )
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean deleteCanvas(String canvasId, String requestingUserId) {
        try {
            CanvasDocument canvas = findCanvasById(canvasId);
            if (canvas == null) return false;
            if (!canvas.getOwnerId().equals(requestingUserId)) return false;

            canvases.deleteOne(eq("_id", new ObjectId(canvasId)));
            inviteCodes.deleteMany(eq("canvasId", canvasId));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Добавляет участника с ролью editor/viewer. Повторный вызов обновляет роль. */
    public void addMemberToCanvas(String canvasId, String userId, String role) {
        if (!CanvasDocument.ROLE_EDITOR.equals(role) && !CanvasDocument.ROLE_VIEWER.equals(role)) {
            role = CanvasDocument.ROLE_EDITOR;
        }
        canvases.updateOne(
            eq("_id", new ObjectId(canvasId)),
            combine(
                addToSet("memberIds", userId),
                set("memberRoles." + userId, role)
            )
        );
    }

    public boolean removeMemberFromCanvas(String canvasId, String userId) {
        try {
            canvases.updateOne(
                eq("_id", new ObjectId(canvasId)),
                combine(pull("memberIds", userId), unset("memberRoles." + userId))
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setMemberRole(String canvasId, String userId, String role) {
        if (!CanvasDocument.ROLE_EDITOR.equals(role) && !CanvasDocument.ROLE_VIEWER.equals(role)) return false;
        try {
            canvases.updateOne(
                eq("_id", new ObjectId(canvasId)),
                set("memberRoles." + userId, role)
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void addElementToCanvas(String canvasId, CanvasElement element) {
        Document elemDoc = new Document()
            .append("elementId", element.getElementId())
            .append("type",      element.getType())
            .append("data",      element.getData())
            .append("addedBy",   element.getAddedBy())
            .append("updatedAt", element.getUpdatedAt());

        canvases.updateOne(
            eq("_id", new ObjectId(canvasId)),
            combine(
                addToSet("elements", elemDoc),
                set("updatedAt", System.currentTimeMillis())
            )
        );
    }

    public void removeElementFromCanvas(String canvasId, String elementId) {
        canvases.updateOne(
            eq("_id", new ObjectId(canvasId)),
            combine(
                pull("elements", new Document("elementId", elementId)),
                set("updatedAt", System.currentTimeMillis())
            )
        );
    }

    public void updateElementInCanvas(String canvasId, String elementId, Document newData) {
        canvases.updateOne(
            combine(eq("_id", new ObjectId(canvasId)), eq("elements.elementId", elementId)),
            combine(
                set("elements.$.data",      newData),
                set("elements.$.updatedAt", System.currentTimeMillis()),
                set("updatedAt",            System.currentTimeMillis())
            )
        );
    }

    @SuppressWarnings("unchecked")
    private CanvasDocument docToCanvas(Document doc) {
        CanvasDocument c = new CanvasDocument();
        c.setId(doc.getObjectId("_id"));
        c.setName(doc.getString("name"));
        c.setOwnerId(doc.getString("ownerId"));
        c.setMemberIds((List<String>) doc.get("memberIds"));
        Document roles = (Document) doc.get("memberRoles");
        if (roles != null) {
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, Object> e : roles.entrySet()) {
                map.put(e.getKey(), String.valueOf(e.getValue()));
            }
            c.setMemberRoles(map);
        }
        c.setCreatedAt(doc.getLong("createdAt"));
        c.setUpdatedAt(doc.getLong("updatedAt"));
        c.setCanvasWidth(doc.getInteger("canvasWidth", 1920));
        c.setCanvasHeight(doc.getInteger("canvasHeight", 1080));

        List<Document> elems = (List<Document>) doc.get("elements");
        if (elems != null) {
            List<CanvasElement> elements = new ArrayList<>();
            for (Document e : elems) {
                CanvasElement ce = new CanvasElement();
                ce.setElementId(e.getString("elementId"));
                ce.setType(e.getString("type"));
                ce.setData((Document) e.get("data"));
                ce.setAddedBy(e.getString("addedBy"));
                elements.add(ce);
            }
            c.setElements(elements);
        }
        return c;
    }

    public InviteCode createInviteCode(String canvasId, String createdBy, String role,
                                       long ttlMillis, int maxUses) {
        String code = generateCode();
        long ttl = Math.max(60_000L, Math.min(ttlMillis, 24 * 60 * 60 * 1000L));
        int uses = Math.max(1, Math.min(maxUses, 50));
        Document doc = new Document()
            .append("code",      code)
            .append("canvasId",  canvasId)
            .append("createdBy", createdBy)
            .append("role",      role)
            .append("expiresAt", new java.util.Date(System.currentTimeMillis() + ttl))
            .append("maxUses",   uses)
            .append("usedCount", 0);
        inviteCodes.insertOne(doc);

        InviteCode ic = new InviteCode(code, canvasId, createdBy, role, ttl, uses);
        ic.setId(doc.getObjectId("_id"));
        return ic;
    }

    public InviteCode createInviteCode(String canvasId, String createdBy) {
        return createInviteCode(canvasId, createdBy, CanvasDocument.ROLE_EDITOR,
                24 * 60 * 60 * 1000L, 1);
    }

    /**
     * Атомарно «сжигает» одно использование кода. Возвращает null, если код истёк,
     * отозван или исчерпан. Безопасен при одновременных запросах.
     */
    public InviteCode consumeInvite(String code) {
        Document doc = inviteCodes.findOneAndUpdate(
            and(
                eq("code", code),
                gt("expiresAt", new java.util.Date()),
                expr(new Document("$lt", List.of("$usedCount", "$maxUses")))
            ),
            inc("usedCount", 1),
            new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
        );
        return doc != null ? docToInvite(doc) : null;
    }

    public boolean revokeInvite(String code, String canvasId) {
        return inviteCodes.deleteOne(and(eq("code", code), eq("canvasId", canvasId)))
                .getDeletedCount() > 0;
    }

    public InviteCode findInviteCode(String code) {
        Document doc = inviteCodes.find(eq("code", code)).first();
        return doc != null ? docToInvite(doc) : null;
    }

    private InviteCode docToInvite(Document doc) {
        InviteCode ic = new InviteCode();
        ic.setId(doc.getObjectId("_id"));
        ic.setCode(doc.getString("code"));
        ic.setCanvasId(doc.getString("canvasId"));
        ic.setCreatedBy(doc.getString("createdBy"));
        ic.setRole(doc.getString("role"));
        java.util.Date expires = doc.getDate("expiresAt");
        ic.setExpiresAt(expires != null ? expires.getTime() : 0);
        ic.setMaxUses(doc.getInteger("maxUses", 1));
        ic.setUsedCount(doc.getInteger("usedCount", 0));
        return ic;
    }

    private String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rng = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public void close() {
        mongoClient.close();
    }
}
