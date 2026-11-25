package org.example.canvasproject.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.DeviceRecord;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.InviteRecord;
import org.example.canvasproject.storage.SqliteStorage;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перенос с настоящей MongoDB. Требует SLATE_INTEGRATION_TESTS=1 и тестовую
 * базу (MONGO_DB содержит "test"). При недоступной Mongo тест пропускается.
 */
class MongoSqliteMigrationIntegrationTest {

    private static final String URI = System.getenv()
            .getOrDefault("MONGO_URI", "mongodb://localhost:27017");
    private static final String DB_NAME = System.getenv().getOrDefault("MONGO_DB", "");

    @TempDir
    Path temp;

    @BeforeEach
    void requireMongoTestDatabase() {
        Assumptions.assumeTrue("1".equals(System.getenv("SLATE_INTEGRATION_TESTS")),
                "Интеграционные тесты выключены (SLATE_INTEGRATION_TESTS != 1)");
        Assumptions.assumeTrue(DB_NAME.contains("test"),
                "MONGO_DB должен указывать на тестовую базу (содержать 'test')");
        Assumptions.assumeTrue(reachable(), "MongoDB недоступна: " + URI);
        wipe();
    }

    @AfterEach
    void wipeAfter() {
        // При выключенных тестах или недоступной Mongo уборка невозможна:
        // не превращаем пропуск в ошибку.
        if (!DB_NAME.contains("test") || !reachable()) return;
        wipe();
    }

    @Test
    void migratesMongoBoardToSqliteAndUpdatesOnSecondRun() {
        ObjectId userId = new ObjectId();
        ObjectId boardId = new ObjectId();
        String owner = userId.toHexString();
        String boardKey = boardId.toHexString();
        String inviteCode = "MONGO234";
        long expandedAt = System.currentTimeMillis() + 3_600_000;

        try (MongoClient client = MongoClients.create(URI)) {
            MongoDatabase db = client.getDatabase(DB_NAME);
            db.getCollection("users").insertOne(new Document("_id", userId)
                    .append("username", "Владелец")
                    .append("deviceKeyHash", "hash-mongo-1")
                    .append("color", "#112233")
                    .append("avatar", "")
                    .append("createdAt", 1111L));
            db.getCollection("canvases").insertOne(new Document("_id", boardId)
                    .append("name", "Mongo доска")
                    .append("ownerId", owner)
                    .append("memberIds", List.of(owner, "guest-1"))
                    .append("memberRoles", new Document(owner, "owner")
                            .append("guest-1", "viewer"))
                    .append("elements", List.of(
                            new Document("elementId", "mongo-el-1")
                                    .append("type", "stroke")
                                    .append("data", Document.parse(
                                            "{\"x\":10,\"y\":20,\"points\":[1,2,3]}"))
                                    .append("addedBy", owner)
                                    .append("updatedAt", 5000L),
                            new Document("elementId", "mongo-el-2")
                                    .append("type", "text")
                                    .append("data", Document.parse("{\"text\":\"привет\"}"))
                                    .append("addedBy", "guest-1")
                                    .append("updatedAt", 5001L)))
                    .append("canvasWidth", 1600)
                    .append("canvasHeight", 900)
                    .append("createdAt", 1000L)
                    .append("updatedAt", 2000L));
            db.getCollection("invite_codes").insertOne(new Document()
                    .append("code", inviteCode)
                    .append("canvasId", boardKey)
                    .append("createdBy", owner)
                    .append("role", "editor")
                    .append("createdAt", 1500L)
                    .append("expiresAt", new Date(expandedAt))
                    .append("maxUses", 3)
                    .append("usedCount", 1));
        }

        Path sqliteFile = temp.resolve("slate.db");
        MigrationRunner.Report report = migrate(
                new MongoMigrationStore(URI, DB_NAME),
                new SqliteMigrationStore(sqliteFile),
                temp.resolve("work"));
        assertTrue(report.ok(), "проблемы: " + report.problems());
        assertEquals(1, report.verifiedCounts().get("boards"));
        assertEquals(2, report.verifiedCounts().get("elements"));
        assertEquals(1, report.verifiedCounts().get("invites"));
        assertEquals(1, report.verifiedCounts().get("devices"));

        try (SqliteStorage storage = new SqliteStorage(sqliteFile)) {
            BoardRecord board = storage.boards().find(boardKey).orElseThrow();
            assertEquals("Mongo доска", board.name());
            assertEquals(owner, board.ownerId());
            assertEquals(1600, board.width());
            assertEquals(900, board.height());
            assertEquals(1000L, board.createdAt());
            assertEquals("viewer", board.roleOf("guest-1"));

            List<ElementRecord> elements = storage.elements().list(boardKey);
            assertEquals(2, elements.size());
            assertEquals("mongo-el-1", elements.get(0).elementId());
            assertEquals(10, elements.get(0).data().getInt("x"));
            assertEquals(5000L, elements.get(0).updatedAt());

            InviteRecord invite = storage.invites().find(inviteCode).orElseThrow();
            assertEquals(1, invite.usedCount());
            assertEquals(3, invite.maxUses());
            assertEquals(1500L, invite.createdAt());
            assertEquals("editor", invite.role());

            DeviceRecord device = storage.devices().find(owner).orElseThrow();
            assertEquals("hash-mongo-1", device.deviceKeyHash());
            assertEquals(1111L, device.createdAt());
        }

        // Меняем источник: повторный запуск обновляет данные, а не дублирует.
        try (MongoClient client = MongoClients.create(URI)) {
            MongoDatabase db = client.getDatabase(DB_NAME);
            db.getCollection("canvases").updateOne(
                    new Document("_id", boardId),
                    new Document("$set", new Document("elements.0.data.x", 99)));
        }
        report = migrate(new MongoMigrationStore(URI, DB_NAME),
                new SqliteMigrationStore(sqliteFile), temp.resolve("work"));
        assertTrue(report.ok(), "проблемы: " + report.problems());

        try (SqliteStorage storage = new SqliteStorage(sqliteFile)) {
            List<ElementRecord> elements = storage.elements().list(boardKey);
            assertEquals(2, elements.size(), "повторный перенос не создаёт элементов-дублей");
            assertEquals(99, elements.get(0).data().getInt("x"),
                    "изменение источника переносится повторным запуском");
            assertTrue(storage.invites().find(inviteCode).isPresent());
        }
    }

    @Test
    void migratesSqliteBoardToMongoAndIsIdempotent() {
        Path sqliteFile = temp.resolve("slate.db");
        String deviceId = "device-local-1";
        String boardId;
        String inviteCode;

        try (SqliteStorage storage = new SqliteStorage(sqliteFile)) {
            storage.devices().create(new DeviceRecord(deviceId, "Локальный", null,
                    null, "hash-local-1", "#abcdef", "", 2222L));
            BoardRecord board = storage.boards().create("Локальная доска",
                    deviceId, 640, 480);
            boardId = board.id();
            storage.elements().add(boardId, new ElementRecord("local-el-1", "figure",
                    new JSONObject().put("shape", "rect").put("w", 100), deviceId, 3333L));
            inviteCode = storage.invites().create(boardId, deviceId, "viewer",
                    3_600_000L, 2).code();
            storage.invites().consume(inviteCode);
        }

        Path work = temp.resolve("work");
        assertEquals(0, MigrationCli.run(new String[]{"sqlite", "mongo",
                        "--dir", temp.toString(), "--db", sqliteFile.toString()}),
                "консольный запуск SQLite → Mongo должен завершиться кодом 0");
        assertTrue(java.nio.file.Files.exists(
                        temp.resolve(MigrationRunner.MANIFEST_NAME)),
                "консольный запуск должен оставить манифест");

        String expectedBoardId = MongoMigrationStore.objectIdFor(boardId, "board");
        String expectedUserId = MongoMigrationStore.objectIdFor(deviceId, "user");

        try (MongoClient client = MongoClients.create(URI)) {
            MongoDatabase db = client.getDatabase(DB_NAME);

            Document board = db.getCollection("canvases")
                    .find(new Document("_id", new ObjectId(expectedBoardId))).first();
            assertNotNull(board, "доска должна получить стабильный ObjectId");
            assertEquals("Локальная доска", board.getString("name"));
            assertEquals(expectedUserId, board.getString("ownerId"));
            assertEquals(List.of(expectedUserId), board.getList("memberIds", String.class));
            assertEquals("owner", ((Document) board.get("memberRoles"))
                    .getString(expectedUserId));
            assertEquals(640, board.getInteger("canvasWidth").intValue());

            List<Document> elements = board.getList("elements", Document.class);
            assertEquals(1, elements.size());
            assertEquals("local-el-1", elements.get(0).getString("elementId"));
            assertEquals(expectedUserId, elements.get(0).getString("addedBy"));
            assertEquals(3333L, elements.get(0).getLong("updatedAt").longValue());

            Document invite = db.getCollection("invite_codes")
                    .find(new Document("code", inviteCode)).first();
            assertNotNull(invite);
            assertEquals(expectedBoardId, invite.getString("canvasId"));
            assertEquals(expectedUserId, invite.getString("createdBy"));
            assertEquals(1, invite.getInteger("usedCount").intValue());
            assertEquals(2, invite.getInteger("maxUses").intValue());

            Document user = db.getCollection("users")
                    .find(new Document("_id", new ObjectId(expectedUserId))).first();
            assertNotNull(user);
            assertEquals("hash-local-1", user.getString("deviceKeyHash"));
            assertEquals(2222L, user.getLong("createdAt").longValue());
        }

        MigrationRunner.Report report = migrate(new SqliteMigrationStore(sqliteFile),
                new MongoMigrationStore(URI, DB_NAME), work);        assertTrue(report.ok(), "проблемы: " + report.problems());

        try (MongoClient client = MongoClients.create(URI)) {
            MongoDatabase db = client.getDatabase(DB_NAME);
            assertEquals(1, db.getCollection("canvases").countDocuments());
            assertEquals(1, db.getCollection("users").countDocuments());
            assertEquals(1, db.getCollection("invite_codes").countDocuments());
            Document board = db.getCollection("canvases")
                    .find(new Document("_id", new ObjectId(expectedBoardId))).first();
            assertNotNull(board);
            assertEquals(1, board.getList("elements", Document.class).size(),
                    "повторный перенос не создаёт элементов-дублей");
        }
    }

    private static MigrationRunner.Report migrate(MigrationStore source,
                                                  MigrationStore target,
                                                  Path work) {
        try (source; target) {
            return new MigrationRunner(source, target, work).run();
        }
    }

    private static boolean reachable() {
        try (MongoClient client = MongoClients.create(URI)) {
            client.getDatabase(DB_NAME).runCommand(new Document("ping", 1));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void wipe() {
        try (MongoClient client = MongoClients.create(URI)) {
            MongoDatabase db = client.getDatabase(DB_NAME);
            for (String name : new String[]{"users", "canvases", "invite_codes",
                    "device_tokens"}) {
                db.getCollection(name).deleteMany(new Document());
            }
        }
    }
}
