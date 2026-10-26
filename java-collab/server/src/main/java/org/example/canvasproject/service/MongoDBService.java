package org.example.canvasproject.service;

import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.example.canvasproject.models.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

public class MongoDBService {

    private static MongoDBService instance;
    private final MongoClient mongoClient;
    private final MongoDatabase db;

    private final MongoCollection<Document> users;
    private final MongoCollection<Document> canvases;
    private final MongoCollection<Document> inviteCodes;

    private MongoDBService(String connectionString, String dbName) {
        mongoClient = MongoClients.create(connectionString);
        db = mongoClient.getDatabase(dbName);
        users       = db.getCollection("users");
        canvases    = db.getCollection("canvases");
        inviteCodes = db.getCollection("invite_codes");

        users.createIndex(Indexes.ascending("username"), new IndexOptions().unique(true));

        try { users.dropIndex("email_1"); } catch (Exception ignored) {}

        try { users.updateMany(eq("email", ""), unset("email")); } catch (Exception ignored) {}
        users.createIndex(Indexes.ascending("email"), new IndexOptions().unique(true).sparse(true));

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
        u.setCreatedAt(doc.getLong("createdAt"));
        return u;
    }

    public CanvasDocument createCanvas(String name, String ownerId, int canvasWidth, int canvasHeight) {
        Document doc = new Document()
            .append("name",         name)
            .append("ownerId",      ownerId)
            .append("memberIds",    List.of(ownerId))
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

    public void addMemberToCanvas(String canvasId, String userId) {
        canvases.updateOne(
            eq("_id", new ObjectId(canvasId)),
            addToSet("memberIds", userId)
        );
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

    public InviteCode createInviteCode(String canvasId, String createdBy) {
        String code = generateCode();
        long ttl = 24 * 60 * 60 * 1000L;
        Document doc = new Document()
            .append("code",      code)
            .append("canvasId",  canvasId)
            .append("createdBy", createdBy)
            .append("expiresAt", new java.util.Date(System.currentTimeMillis() + ttl))
            .append("maxUses",   50)
            .append("usedCount", 0);
        inviteCodes.insertOne(doc);

        InviteCode ic = new InviteCode(code, canvasId, createdBy, ttl, 50);
        ic.setId(doc.getObjectId("_id"));
        return ic;
    }

    public InviteCode findInviteCode(String code) {
        Document doc = inviteCodes.find(eq("code", code)).first();
        if (doc == null) return null;
        InviteCode ic = new InviteCode();
        ic.setId(doc.getObjectId("_id"));
        ic.setCode(doc.getString("code"));
        ic.setCanvasId(doc.getString("canvasId"));
        ic.setCreatedBy(doc.getString("createdBy"));
        java.util.Date expires = doc.getDate("expiresAt");
        ic.setExpiresAt(expires != null ? expires.getTime() : 0);
        ic.setMaxUses(doc.getInteger("maxUses", 0));
        ic.setUsedCount(doc.getInteger("usedCount", 0));
        return ic;
    }

    public void incrementInviteUsage(String code) {
        inviteCodes.updateOne(eq("code", code), inc("usedCount", 1));
    }

    private String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(6);
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public void close() {
        mongoClient.close();
    }
}
