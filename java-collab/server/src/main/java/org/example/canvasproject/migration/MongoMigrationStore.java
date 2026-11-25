package org.example.canvasproject.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.DeviceRecord;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.InviteRecord;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

/**
 * Mongo-хранилище переноса. Работает напрямую с коллекциями, потому что
 * репозитории не позволяют сохранить исходные `_id`, время создания и счётчик
 * использований приглашения.
 *
 * <p>Идентификаторы, которые уже являются ObjectId (24 hex), сохраняются.
 * Остальные (UUID локальных досок и устройств) детерминированно превращаются в
 * ObjectId через SHA-256 от «пространство:идентификатор» — повторный запуск
 * получает те же значения и не создаёт дублей. Ссылки на пользователей внутри
 * доски и приглашений переписываются на новые id.
 */
public final class MongoMigrationStore implements MigrationStore {

    private static final String USERS = "users";
    private static final String CANVASES = "canvases";
    private static final String INVITES = "invite_codes";

    private final MongoClient client;
    private final MongoDatabase database;
    private final String description;

    public MongoMigrationStore(String uri, String databaseName) {
        String safeUri = sanitizeUri(uri);
        this.description = "mongo:" + safeUri + "/" + databaseName;
        try {
            this.client = MongoClients.create(uri);
            this.database = client.getDatabase(databaseName);
            database.runCommand(new Document("ping", 1));
        } catch (RuntimeException e) {
            throw new MigrationException("MongoDB недоступна (" + description
                    + ", код ошибки: " + errorCode(e) + ")", e);
        }
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public MigrationData read() {
        try {
            List<String> warnings = new ArrayList<>();

            List<DeviceRecord> devices = new ArrayList<>();
            for (Document doc : collection(USERS).find()) {
                DeviceRecord device = toDevice(doc);
                if (device != null) devices.add(device);
            }

            List<BoardSnapshot> boards = new ArrayList<>();
            Set<String> boardIds = new HashSet<>();
            for (Document doc : collection(CANVASES).find()) {
                BoardSnapshot board = toBoard(doc);
                if (board == null) continue;
                boards.add(board);
                boardIds.add(board.id());
            }

            List<InviteRecord> invites = new ArrayList<>();
            int orphans = 0;
            for (Document doc : collection(INVITES).find()) {
                InviteRecord invite = toInvite(doc);
                if (invite == null) continue;
                if (invite.boardId() == null || !boardIds.contains(invite.boardId())) {
                    orphans++;
                    continue;
                }
                invites.add(invite);
            }
            if (orphans > 0) {
                warnings.add("Пропущено приглашений без доски: " + orphans);
            }

            return new MigrationData(boards, invites, devices, warnings);
        } catch (RuntimeException e) {
            throw new MigrationException("Не удалось прочитать MongoDB (" + description
                    + ", код ошибки: " + errorCode(e) + ")", e);
        }
    }

    @Override
    public WriteResult write(MigrationData data) {
        ensureIndexes();
        List<String> warnings = new ArrayList<>();

        Map<String, String> userIds = new LinkedHashMap<>();
        for (DeviceRecord device : data.devices()) {
            requireId(device.id(), "устройства");
            userIds.put(device.id(), objectIdFor(device.id(), "user"));
        }
        Map<String, String> boardIds = new LinkedHashMap<>();
        for (BoardSnapshot board : data.boards()) {
            requireId(board.id(), "доски");
            boardIds.put(board.id(), objectIdFor(board.id(), "board"));
        }

        int writtenInvites = 0;
        try {
            for (DeviceRecord device : data.devices()) {
                Document doc = toUserDocument(device, userIds.get(device.id()));
                collection(USERS).replaceOne(eq("_id", doc.getObjectId("_id")), doc,
                        new ReplaceOptions().upsert(true));
            }
            for (BoardSnapshot board : data.boards()) {
                Document doc = toBoardDocument(board, boardIds.get(board.id()), userIds);
                collection(CANVASES).replaceOne(eq("_id", doc.getObjectId("_id")), doc,
                        new ReplaceOptions().upsert(true));
            }
            int orphans = 0;
            for (InviteRecord invite : data.invites()) {
                if (invite.code() == null || invite.boardId() == null
                        || !boardIds.containsKey(invite.boardId())) {
                    orphans++;
                    continue;
                }
                String inviteId = objectIdFor(invite.code(), "invite");
                Document doc = toInviteDocument(invite, inviteId,
                        boardIds.get(invite.boardId()), userIds);
                collection(INVITES).replaceOne(eq("_id", doc.getObjectId("_id")), doc,
                        new ReplaceOptions().upsert(true));
                writtenInvites++;
            }
            if (orphans > 0) {
                warnings.add("Пропущено приглашений без доски: " + orphans);
            }
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                throw new MigrationException("Конфликт уникального индекса MongoDB: "
                        + "ключ устройства, email или код приглашения уже заняты. "
                        + "Устраните дубликат в назначении и повторите перенос.", e);
            }
            throw new MigrationException("Не удалось записать данные в MongoDB "
                    + "(код ошибки: " + e.getError().getCode() + ")", e);
        } catch (MongoCommandException e) {
            throw new MigrationException("MongoDB отклонила запись (код ошибки: "
                    + e.getErrorCode() + ")", e);
        }

        return new WriteResult(boardIds, userIds, data.boards().size(),
                data.elementCount(), writtenInvites, data.devices().size(), warnings);
    }

    @Override
    public List<Path> backup(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("mongo-" + System.currentTimeMillis() + ".json");
        JSONObject root = new JSONObject();
        root.put("database", database.getName());
        root.put("createdAt", Instant.now().toString());
        JSONObject collections = new JSONObject();
        for (String name : new String[]{USERS, CANVASES, INVITES}) {
            JSONArray items = new JSONArray();
            for (Document doc : collection(name).find()) {
                items.put(new JSONObject(doc.toJson()));
            }
            collections.put(name, items);
        }
        root.put("collections", collections);
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
        return List.of(file);
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * Стабильный ObjectId для исходного id. Готовый 24-символьный hex
     * сохраняется как есть; остальное хешируется вместе с пространством имён,
     * чтобы доска, пользователь и приглашение не могли совпасть.
     */
    static String objectIdFor(String sourceId, String namespace) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("Пустой идентификатор для переноса в Mongo");
        }
        if (sourceId.matches("[0-9a-fA-F]{24}")) {
            return sourceId.toLowerCase(Locale.ROOT);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((namespace + ":" + sourceId)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private void ensureIndexes() {
        try {
            collection(USERS).createIndex(Indexes.ascending("deviceKeyHash"),
                    new IndexOptions().unique(true).sparse(true));
            collection(INVITES).createIndex(Indexes.ascending("code"),
                    new IndexOptions().unique(true));
        } catch (RuntimeException e) {
            throw new MigrationException("Не удалось подготовить индексы MongoDB "
                    + "(возможны дубликаты ключей устройств или кодов): "
                    + errorCode(e), e);
        }
    }

    private MongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    private static DeviceRecord toDevice(Document doc) {
        ObjectId id = doc.getObjectId("_id");
        if (id == null) return null;
        return new DeviceRecord(
                id.toHexString(),
                orEmpty(doc.getString("username")),
                blankToNull(doc.getString("email")),
                blankToNull(doc.getString("passwordHash")),
                blankToNull(doc.getString("deviceKeyHash")),
                doc.getString("color"),
                doc.getString("avatar"),
                createdAt(doc, id));
    }

    private static BoardSnapshot toBoard(Document doc) {
        ObjectId id = doc.getObjectId("_id");
        if (id == null) return null;

        List<String> members = new ArrayList<>();
        Object rawMembers = doc.get("memberIds");
        if (rawMembers instanceof List<?> list) {
            for (Object member : list) {
                if (member != null) members.add(String.valueOf(member));
            }
        }
        Map<String, String> roles = new LinkedHashMap<>();
        Object rawRoles = doc.get("memberRoles");
        if (rawRoles instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    roles.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }

        List<ElementRecord> elements = new ArrayList<>();
        Object rawElements = doc.get("elements");
        if (rawElements instanceof List<?> list) {
            for (Object raw : list) {
                ElementRecord element = toElement(raw);
                if (element != null) elements.add(element);
            }
        }

        BoardRecord board = new BoardRecord(
                id.toHexString(),
                orEmpty(doc.getString("name")),
                orEmpty(doc.getString("ownerId")),
                integer(doc.get("canvasWidth"), 1920),
                integer(doc.get("canvasHeight"), 1080),
                createdAt(doc, id),
                number(doc.get("updatedAt")),
                members, roles);
        return new BoardSnapshot(board, elements);
    }

    private static ElementRecord toElement(Object raw) {
        if (!(raw instanceof Document doc)) return null;
        String elementId = doc.getString("elementId");
        if (elementId == null || elementId.isBlank()) return null;
        return new ElementRecord(
                elementId,
                orEmpty(doc.getString("type"), "figure"),
                toJson(doc.get("data")),
                orEmpty(doc.getString("addedBy")),
                number(doc.get("updatedAt")));
    }

    private static InviteRecord toInvite(Document doc) {
        if (doc.getObjectId("_id") == null) return null;
        String code = doc.getString("code");
        if (code == null || code.isBlank()) return null;
        return new InviteRecord(
                code,
                doc.getString("canvasId"),
                orEmpty(doc.getString("createdBy")),
                orEmpty(doc.getString("role"), "editor"),
                number(doc.get("createdAt")),
                date(doc.get("expiresAt")),
                integer(doc.get("maxUses"), 1),
                integer(doc.get("usedCount"), 0));
    }

    private static Document toUserDocument(DeviceRecord device, String targetId) {
        Document doc = new Document("_id", new ObjectId(targetId))
                .append("username", device.username() == null ? "" : device.username())
                .append("createdAt", device.createdAt());
        if (device.email() != null) doc.append("email", device.email());
        if (device.passwordHash() != null) doc.append("passwordHash", device.passwordHash());
        if (device.deviceKeyHash() != null) doc.append("deviceKeyHash", device.deviceKeyHash());
        if (device.color() != null) doc.append("color", device.color());
        if (device.avatar() != null) doc.append("avatar", device.avatar());
        return doc;
    }

    private static Document toBoardDocument(BoardSnapshot snapshot, String targetId,
                                            Map<String, String> userIds) {
        BoardRecord board = snapshot.board();
        List<String> members = new ArrayList<>();
        for (String member : board.memberIds()) {
            if (member != null) {
                members.add(MigrationVerification.mapId(userIds, member));
            }
        }
        Document roles = new Document();
        board.memberRoles().forEach((user, role) -> {
            if (user != null && role != null) {
                roles.append(MigrationVerification.mapId(userIds, user), role);
            }
        });
        List<Document> elements = new ArrayList<>();
        for (ElementRecord element : snapshot.elements()) {
            elements.add(new Document("elementId", element.elementId())
                    .append("type", element.elementType())
                    .append("data", Document.parse(element.data().toString()))
                    .append("addedBy", element.addedBy() == null ? null
                            : MigrationVerification.mapId(userIds, element.addedBy()))
                    .append("updatedAt", element.updatedAt()));
        }
        return new Document("_id", new ObjectId(targetId))
                .append("name", board.name() == null ? "" : board.name())
                .append("ownerId", board.ownerId() == null ? null
                        : MigrationVerification.mapId(userIds, board.ownerId()))
                .append("memberIds", members)
                .append("memberRoles", roles)
                .append("elements", elements)
                .append("canvasWidth", board.width())
                .append("canvasHeight", board.height())
                .append("createdAt", board.createdAt())
                .append("updatedAt", board.updatedAt());
    }

    private static Document toInviteDocument(InviteRecord invite, String targetId,
                                             String targetBoardId,
                                             Map<String, String> userIds) {
        return new Document("_id", new ObjectId(targetId))
                .append("code", invite.code())
                .append("canvasId", targetBoardId)
                .append("createdBy", invite.createdBy() == null ? ""
                        : MigrationVerification.mapId(userIds, invite.createdBy()))
                .append("role", invite.role() == null ? "editor" : invite.role())
                .append("createdAt", invite.createdAt())
                .append("expiresAt", new Date(invite.expiresAt()))
                .append("maxUses", invite.maxUses())
                .append("usedCount", invite.usedCount());
    }

    private static JSONObject toJson(Object raw) {
        try {
            if (raw instanceof Document doc) return new JSONObject(doc.toJson());
            if (raw instanceof String string) return new JSONObject(string);
        } catch (JSONException e) {
            throw new MigrationException("Повреждённые данные элемента в MongoDB", e);
        }
        return new JSONObject();
    }

    private static long createdAt(Document doc, ObjectId id) {
        Object raw = doc.get("createdAt");
        if (raw != null) return number(raw);
        return id == null ? 0L : id.getTimestamp() * 1000L;
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof Date date) return date.getTime();
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static long date(Object value) {
        if (value instanceof Date date) return date.getTime();
        return number(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orEmpty(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String errorCode(RuntimeException e) {
        if (e instanceof MongoCommandException command) return String.valueOf(command.getErrorCode());
        if (e instanceof MongoWriteException write) return String.valueOf(write.getError().getCode());
        return e.getClass().getSimpleName();
    }

    private static void requireId(String id, String entity) {
        if (id == null || id.isBlank()) {
            throw new MigrationException("У " + entity + " нет идентификатора");
        }
    }

    /** Убирает логин и пароль из URI, чтобы они не попали в манифест. */
    static String sanitizeUri(String uri) {
        if (uri == null) return "";
        int scheme = uri.indexOf("://");
        int at = uri.indexOf('@');
        if (scheme >= 0 && at > scheme + 3) {
            return uri.substring(0, scheme + 3) + "***" + uri.substring(at);
        }
        return uri;
    }
}
