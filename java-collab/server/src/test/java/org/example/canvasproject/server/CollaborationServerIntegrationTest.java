package org.example.canvasproject.server;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Интеграционный тест протокола: реальный WebSocket-сервер и настоящая MongoDB.
 *
 * По умолчанию ПРОПУСКАЕТСЯ, чтобы обычная сборка не зависела от базы.
 * Запуск:
 *   SLATE_INTEGRATION_TESTS=1 MONGO_DB=canvas_collab_test \
 *   JAVA_HOME=/usr/lib/jvm/openjdk21 ./mvnw -f java-collab/server/pom.xml verify
 *
 * Защита от случайного удаления рабочих данных: требуется, чтобы имя базы
 * содержало "test" — перед тестом она очищается.
 */
class CollaborationServerIntegrationTest {

    private static final String MONGO_URI =
            System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
    private static final String DB_NAME = System.getenv().getOrDefault("MONGO_DB", "");

    private static CollaborationServer server;

    @BeforeAll
    static void startServer() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("SLATE_INTEGRATION_TESTS")),
                "Интеграционные тесты выключены (SLATE_INTEGRATION_TESTS != 1)");
        Assumptions.assumeTrue(DB_NAME.contains("test"),
                "MONGO_DB должен указывать на тестовую базу (содержать 'test')");

        dropTestDatabase();

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new CollaborationServer(port);
        server.start();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop(1000);
            server = null;
        }
        if (DB_NAME.contains("test")) {
            dropTestDatabase();
        }
    }

    private static void dropTestDatabase() {
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            client.getDatabase(DB_NAME).drop();
        }
    }

    @Test
    void twoClientsShareBoardAndEditorRights() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        TestClient guest = TestClient.connect(server.getPort());
        try {
            owner.auth("Владелец");
            guest.auth("Гость");

            String canvasId = owner.createCanvas("Совместная доска");
            owner.join(canvasId);
            String invite = owner.generateInvite(canvasId, "editor", 1);

            guest.sendJson(new JSONObject()
                    .put("type", "JOIN_BY_CODE")
                    .put("code", invite));
            JSONObject joined = guest.await("JOINED_CANVAS");
            assertEquals(canvasId, joined.getString("canvasId"));
            assertEquals("editor", joined.getString("role"));

            owner.await("USER_JOINED");

            // Владелец добавляет фигуру — гость получает её без перезагрузки.
            owner.sendJson(addElement(canvasId, "owner-figure", "figure",
                    new JSONObject().put("figureType", "rect").put("x", 10).put("y", 20)));
            JSONObject added = guest.await("ELEMENT_ADDED");
            assertEquals("owner-figure", added.getString("elementId"));
            assertEquals("figure", added.getString("elementType"));

            // Гость-редактор добавляет линию — владелец тоже видит изменение.
            guest.sendJson(addElement(canvasId, "guest-line", "line",
                    new JSONObject().put("points", new org.json.JSONArray().put(0).put(0).put(10).put(10))));
            JSONObject guestAdded = owner.await("ELEMENT_ADDED");
            assertEquals("guest-line", guestAdded.getString("elementId"));

            // Редактор может менять элементы, но не переименовывать доску.
            guest.sendJson(new JSONObject()
                    .put("type", "UPDATE_ELEMENT")
                    .put("canvasId", canvasId)
                    .put("elementId", "owner-figure")
                    .put("data", new JSONObject().put("x", 99)));
            owner.await("ELEMENT_UPDATED");

            guest.sendJson(new JSONObject()
                    .put("type", "RENAME_CANVAS")
                    .put("canvasId", canvasId)
                    .put("name", "Взлом"));
            assertTrue(guest.await("ERROR").getString("message").contains("owner"));
        } finally {
            owner.close();
            guest.close();
        }
    }

    @Test
    void viewerCannotModifyAndInvalidOrRevokedCodesAreRejected() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        TestClient viewer = TestClient.connect(server.getPort());
        TestClient outsider = TestClient.connect(server.getPort());
        try {
            owner.auth("Владелец-2");
            viewer.auth("Наблюдатель");
            outsider.auth("Посторонний");

            String canvasId = owner.createCanvas("Только просмотр");
            owner.join(canvasId);
            String viewerCode = owner.generateInvite(canvasId, "viewer", 1);

            viewer.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", viewerCode));
            assertEquals("viewer", viewer.await("JOINED_CANVAS").getString("role"));

            viewer.drain();
            viewer.sendJson(addElement(canvasId, "viewer-element", "figure", new JSONObject()));
            assertTrue(viewer.await("ERROR").getString("message").contains("редактор"));
            viewer.assertNoMessage("ELEMENT_ADDED", 500);
            owner.assertNoMessage("ELEMENT_ADDED", 300);

            // Редактор не может выдать приглашение редактора.
            TestClient editor = TestClient.connect(server.getPort());
            try {
                editor.auth("Редактор");
                String editorCode = owner.generateInvite(canvasId, "editor", 1);
                editor.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", editorCode));
                assertEquals("editor", editor.await("JOINED_CANVAS").getString("role"));

                editor.sendJson(new JSONObject()
                        .put("type", "GEN_INVITE")
                        .put("canvasId", canvasId)
                        .put("role", "editor"));
                assertTrue(editor.await("ERROR").getString("message").contains("прав"));
            } finally {
                editor.close();
            }

            // Несуществующий код.
            outsider.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", "ZZZZZZZZ"));
            assertTrue(outsider.await("ERROR").getString("message").contains("Invalid"));

            // Отозванный код.
            String revoked = owner.generateInvite(canvasId, "viewer", 5);
            owner.sendJson(new JSONObject()
                    .put("type", "REVOKE_INVITE")
                    .put("canvasId", canvasId)
                    .put("code", revoked));
            owner.await("INVITE_REVOKED");

            outsider.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", revoked));
            assertTrue(outsider.await("ERROR").getString("message").contains("Invalid"));
        } finally {
            owner.close();
            viewer.close();
            outsider.close();
        }
    }

    private static JSONObject addElement(String canvasId, String id, String type, JSONObject data) {
        return new JSONObject()
                .put("type", "ADD_ELEMENT")
                .put("canvasId", canvasId)
                .put("elementId", id)
                .put("elementType", type)
                .put("data", data);
    }

    /** Минимальный WebSocket-клиент с очередью сообщений и таймаутами. */
    private static final class TestClient extends WebSocketClient {
        private final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        private volatile Throwable lastError;

        private TestClient(URI uri) {
            super(uri);
        }

        static TestClient connect(int port) throws Exception {
            TestClient client = new TestClient(new URI("ws://127.0.0.1:" + port));
            if (!client.connectBlocking(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("WebSocket не подключился к порту " + port);
            }
            return client;
        }

        @Override public void onOpen(ServerHandshake handshake) {}
        @Override public void onMessage(String message) { inbox.add(new JSONObject(message)); }
        @Override public void onClose(int code, String reason, boolean remote) {}
        @Override public void onError(Exception ex) { lastError = ex; }

        void sendJson(JSONObject message) {
            send(message.toString());
        }

        void auth(String name) throws Exception {
            String key = "test-" + UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            sendJson(new JSONObject()
                    .put("type", "AUTH_DEVICE")
                    .put("deviceKey", key)
                    .put("deviceName", name));
            JSONObject ok = await("AUTH_OK");
            assertNotNull(ok.getString("token"));
        }

        String createCanvas(String name) throws Exception {
            sendJson(new JSONObject().put("type", "CREATE_CANVAS").put("name", name));
            JSONObject created = await("CANVAS_CREATED");
            return created.getJSONObject("canvas").getString("id");
        }

        void join(String canvasId) throws Exception {
            sendJson(new JSONObject().put("type", "JOIN_CANVAS").put("canvasId", canvasId));
            await("JOINED_CANVAS");
        }

        String generateInvite(String canvasId, String role, int maxUses) throws Exception {
            sendJson(new JSONObject()
                    .put("type", "GEN_INVITE")
                    .put("canvasId", canvasId)
                    .put("role", role)
                    .put("maxUses", maxUses));
            return await("INVITE_CODE").getString("code");
        }

        JSONObject await(String type) throws Exception {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                JSONObject message = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (message == null) continue;
                if (type.equals(message.optString("type"))) return message;
            }
            if (lastError != null) throw new AssertionError("WebSocket error", lastError);
            return fail("Не дождались сообщения " + type);
        }

        void assertNoMessage(String type, long millis) throws Exception {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                JSONObject message = inbox.poll(50, TimeUnit.MILLISECONDS);
                if (message != null && type.equals(message.optString("type"))) {
                    fail("Неожиданное сообщение " + type + ": " + message);
                }
            }
        }

        void drain() {
            inbox.clear();
        }
    }
}
