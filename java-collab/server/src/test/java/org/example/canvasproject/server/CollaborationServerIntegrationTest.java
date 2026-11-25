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

    @Test
    void updateWithoutDataIsRejectedAndKeepsElement() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        try {
            owner.auth("Владелец-3");
            String canvasId = owner.createCanvas("Защита данных");
            owner.join(canvasId);
            owner.sendJson(addElement(canvasId, "keep-me", "figure",
                    new JSONObject().put("x", 10).put("y", 20).put("figureType", "rect")));

            owner.sendJson(new JSONObject()
                    .put("type", "UPDATE_ELEMENT")
                    .put("canvasId", canvasId)
                    .put("elementId", "keep-me"));
            assertTrue(owner.await("ERROR").getString("message").toLowerCase().contains("data"),
                    "пустой UPDATE_ELEMENT должен вернуть ошибку");

            // Перечитываем доску: данные не затёрты.
            owner.sendJson(new JSONObject().put("type", "JOIN_CANVAS").put("canvasId", canvasId));
            JSONObject joined = owner.await("JOINED_CANVAS");
            JSONObject element = joined.getJSONArray("elements").getJSONObject(0);
            assertEquals(10, element.getJSONObject("data").getInt("x"));
            assertEquals(20, element.getJSONObject("data").getInt("y"));
        } finally {
            owner.close();
        }
    }

    @Test
    void leaveCanvasWithWrongIdStillLeavesRealRoom() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        TestClient guest = TestClient.connect(server.getPort());
        try {
            owner.auth("Владелец-4");
            guest.auth("Гость-4");
            String canvasId = owner.createCanvas("Выход из комнаты");
            owner.join(canvasId);
            String invite = owner.generateInvite(canvasId, "editor", 1);
            guest.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", invite));
            guest.await("JOINED_CANVAS");
            owner.await("USER_JOINED");
            owner.drain();

            // Клиент прислал чужой id: выйти нужно из фактической комнаты.
            guest.sendJson(new JSONObject()
                    .put("type", "LEAVE_CANVAS")
                    .put("canvasId", "000000000000000000000000"));
            assertEquals(canvasId, guest.await("LEFT_CANVAS").getString("canvasId"));
            owner.await("USER_LEFT");

            owner.sendJson(addElement(canvasId, "after-leave", "figure", new JSONObject()));
            guest.assertNoMessage("ELEMENT_ADDED", 500);

            // Повторный вход снова даёт живые события.
            guest.sendJson(new JSONObject().put("type", "JOIN_CANVAS").put("canvasId", canvasId));
            guest.await("JOINED_CANVAS");
            owner.sendJson(addElement(canvasId, "after-rejoin", "figure", new JSONObject()));
            assertEquals("after-rejoin", guest.await("ELEMENT_ADDED").getString("elementId"));
        } finally {
            owner.close();
            guest.close();
        }
    }

    @Test
    void canvasSizeIsStoredBroadcastAndProtectedFromViewers() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        TestClient viewer = TestClient.connect(server.getPort());
        try {
            owner.auth("Владелец размера");
            viewer.auth("Наблюдатель размера");
            String canvasId = owner.createCanvas("Размер");
            owner.join(canvasId);

            String code = owner.generateInvite(canvasId, "viewer", 1);
            viewer.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", code));
            viewer.await("JOINED_CANVAS");
            owner.await("USER_JOINED");
            viewer.drain();

            owner.sendJson(new JSONObject()
                    .put("type", "RESIZE_CANVAS")
                    .put("canvasId", canvasId)
                    .put("width", 2400)
                    .put("height", 1400));

            JSONObject resized = owner.await("CANVAS_RESIZED");
            assertEquals(2400, resized.getInt("canvasWidth"));
            assertEquals(1400, resized.getInt("canvasHeight"));
            assertEquals(2400, viewer.await("CANVAS_RESIZED").getInt("canvasWidth"));

            // Наблюдателю размер менять нельзя.
            viewer.sendJson(new JSONObject()
                    .put("type", "RESIZE_CANVAS")
                    .put("canvasId", canvasId)
                    .put("width", 1000)
                    .put("height", 1000));
            assertTrue(viewer.await("ERROR").getString("message").contains("редактор"));
            viewer.assertNoMessage("CANVAS_RESIZED", 400);

            // Размер сохранился на сервере и приходит при следующем входе.
            owner.sendJson(new JSONObject().put("type", "JOIN_CANVAS").put("canvasId", canvasId));
            JSONObject rejoined = owner.await("JOINED_CANVAS");
            assertEquals(2400, rejoined.getInt("canvasWidth"));
            assertEquals(1400, rejoined.getInt("canvasHeight"));
        } finally {
            owner.close();
            viewer.close();
        }
    }

    @Test
    void repeatedAddWithSameIdReplacesInsteadOfDuplicating() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        try {
            owner.auth("Владелец дублей");
            String canvasId = owner.createCanvas("Дубли");
            owner.join(canvasId);

            owner.sendJson(addElement(canvasId, "same-id", "figure",
                    new JSONObject().put("v", 1)));
            owner.sendJson(addElement(canvasId, "same-id", "figure",
                    new JSONObject().put("v", 2)));

            owner.sendJson(new JSONObject().put("type", "JOIN_CANVAS").put("canvasId", canvasId));
            JSONObject joined = owner.await("JOINED_CANVAS");
            var elements = joined.getJSONArray("elements");
            int matches = 0;
            for (int i = 0; i < elements.length(); i++) {
                JSONObject el = elements.getJSONObject(i);
                if ("same-id".equals(el.getString("elementId"))) {
                    matches++;
                    assertEquals(2, el.getJSONObject("data").getInt("v"));
                }
            }
            assertEquals(1, matches, "элемент с одним id должен храниться один раз");
        } finally {
            owner.close();
        }
    }

    @Test
    void assetsAreAddressedByHashAndRequireAuthentication() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        try {
            owner.auth("Ассеты");
            byte[] bytes = new byte[]{1, 2, 3, 4, 5};
            String hash = org.example.canvasproject.storage.FileAssetStore.hash(bytes);
            String payload = java.util.Base64.getEncoder().encodeToString(bytes);

            owner.sendJson(new JSONObject().put("type", "ASSET_PUT")
                    .put("assetId", hash).put("mime", "image/png").put("data", payload));
            assertEquals(hash, owner.await("ASSET_STORED").getString("assetId"));

            owner.sendJson(new JSONObject().put("type", "ASSET_GET").put("assetId", hash));
            assertEquals(payload, owner.await("ASSET_DATA").getString("data"));

            // Чужой хеш не должен пройти: адрес — это содержимое файла.
            owner.sendJson(new JSONObject().put("type", "ASSET_PUT")
                    .put("assetId", "a".repeat(64)).put("data", payload));
            owner.await("ERROR");

            owner.sendJson(new JSONObject().put("type", "ASSET_GET")
                    .put("assetId", "0".repeat(64)));
            owner.await("ERROR");

            TestClient anonymous = TestClient.connect(server.getPort());
            try {
                anonymous.sendJson(new JSONObject().put("type", "ASSET_GET").put("assetId", hash));
                anonymous.await("ERROR");
            } finally {
                anonymous.close();
            }
        } finally {
            owner.close();
        }
    }

    @Test
    void batchUpdateAndDeleteAreAtomicAndBroadcastToOtherClients() throws Exception {
        TestClient owner = TestClient.connect(server.getPort());
        TestClient guest = TestClient.connect(server.getPort());
        TestClient viewer = TestClient.connect(server.getPort());
        try {
            owner.auth("Владелец группы");
            guest.auth("Редактор группы");
            viewer.auth("Наблюдатель группы");
            String canvasId = owner.createCanvas("Групповые операции");
            owner.join(canvasId);
            String editorCode = owner.generateInvite(canvasId, "editor", 1);
            guest.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", editorCode));
            guest.await("JOINED_CANVAS");
            String viewerCode = owner.generateInvite(canvasId, "viewer", 1);
            viewer.sendJson(new JSONObject().put("type", "JOIN_BY_CODE").put("code", viewerCode));
            viewer.await("JOINED_CANVAS");
            owner.await("USER_JOINED");
            owner.drain();

            for (int i = 0; i < 50; i++) {
                owner.sendJson(addElement(canvasId, "group-" + i, "figure",
                        new JSONObject().put("x", i).put("y", i).put("width", 10).put("height", 10)));
            }
            for (int i = 0; i < 50; i++) {
                assertEquals("group-" + i, guest.await("ELEMENT_ADDED").getString("elementId"));
            }
            guest.drain();
            viewer.drain();

            // Один пакет — 50 обновлений; второй клиент видит итог, а не 50 событий.
            org.json.JSONArray updates = new org.json.JSONArray();
            for (int i = 0; i < 50; i++) {
                updates.put(new JSONObject()
                        .put("elementId", "group-" + i)
                        .put("data", new JSONObject().put("x", 500).put("y", 600)));
            }
            owner.sendJson(new JSONObject()
                    .put("type", "UPDATE_ELEMENTS")
                    .put("canvasId", canvasId)
                    .put("updates", updates));
            JSONObject updated = guest.await("ELEMENTS_UPDATED");
            assertEquals(50, updated.getJSONArray("updates").length());

            // Пакет с повторяющимся id отклоняется целиком и не меняет доску.
            owner.sendJson(new JSONObject()
                    .put("type", "UPDATE_ELEMENTS")
                    .put("canvasId", canvasId)
                    .put("updates", new org.json.JSONArray()
                            .put(new JSONObject().put("elementId", "group-0")
                                    .put("data", new JSONObject().put("x", 9999)))
                            .put(new JSONObject().put("elementId", "group-0")
                                    .put("data", new JSONObject().put("x", 9999)))));
            assertTrue(owner.await("ERROR").getString("message").contains("elementId"));

            owner.sendJson(new JSONObject().put("type", "JOIN_CANVAS").put("canvasId", canvasId));
            JSONObject joined = owner.await("JOINED_CANVAS");
            for (int i = 0; i < joined.getJSONArray("elements").length(); i++) {
                JSONObject data = joined.getJSONArray("elements").getJSONObject(i).getJSONObject("data");
                assertEquals(500, data.getInt("x"), "пакет должен примениться атомарно");
                assertEquals(600, data.getInt("y"));
            }

            // Наблюдатель не может менять элементы даже пакетом.
            viewer.sendJson(new JSONObject()
                    .put("type", "DELETE_ELEMENTS")
                    .put("canvasId", canvasId)
                    .put("elementIds", new org.json.JSONArray().put("group-0")));
            assertTrue(viewer.await("ERROR").getString("message").contains("редактор"));

            // Пакетное удаление 25 элементов приходит одним сообщением.
            org.json.JSONArray ids = new org.json.JSONArray();
            for (int i = 0; i < 25; i++) ids.put("group-" + i);
            owner.sendJson(new JSONObject()
                    .put("type", "DELETE_ELEMENTS")
                    .put("canvasId", canvasId)
                    .put("elementIds", ids));
            JSONObject deleted = guest.await("ELEMENTS_DELETED");
            assertEquals(25, deleted.getJSONArray("elementIds").length());

            owner.sendJson(new JSONObject().put("type", "JOIN_CANVAS").put("canvasId", canvasId));
            assertEquals(25, owner.await("JOINED_CANVAS").getJSONArray("elements").length());
        } finally {
            owner.close();
            guest.close();
            viewer.close();
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
