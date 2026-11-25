package org.example.canvasproject.transport;

import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.SqliteStorage;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Solo-режим обязан идти через тот же слой команд и те же проверки прав,
 * что и WebSocket-сервер, только без сокета и порта.
 */
class InProcessTransportTest {

    @TempDir
    Path temp;

    private SqliteStorage storage;

    @BeforeEach
    void open() {
        storage = new SqliteStorage(temp.resolve("solo.db"));
    }

    @AfterEach
    void close() {
        storage.close();
    }

    private List<JSONObject> conversation(String userId, String role, JSONObject... messages) {
        LocalServer server = new LocalServer(storage, userId, "Локальный", role);
        List<JSONObject> answers = new ArrayList<>();
        for (JSONObject message : messages) answers.add(server.handle(message));
        return answers;
    }

    private static JSONObject message(String type) {
        return new JSONObject().put("type", type);
    }

    @Test
    void createsBoardAndKeepsElementsAcrossRequests() {
        List<JSONObject> answers = conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("CREATE_CANVAS").put("name", "Без названия")
                        .put("canvasWidth", 1600).put("canvasHeight", 900));
        JSONObject created = answers.get(0);
        assertEquals("CANVAS_CREATED", created.getString("type"));
        String boardId = created.getJSONObject("canvas").getString("id");

        conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("ADD_ELEMENT").put("canvasId", boardId).put("elementId", "el-1")
                        .put("elementType", "note")
                        .put("data", new JSONObject().put("text", "привет")));

        JSONObject joined = conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("JOIN_CANVAS").put("canvasId", boardId)).get(0);
        assertEquals("JOINED_CANVAS", joined.getString("type"));
        assertEquals(1, joined.getJSONArray("elements").length());
        assertEquals("привет", joined.getJSONArray("elements").getJSONObject(0)
                .getJSONObject("data").getString("text"));
    }

    @Test
    void emptyUpdateIsRejectedAndDoesNotEraseElement() {
        String boardId = createBoard("Права");
        conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("ADD_ELEMENT").put("canvasId", boardId).put("elementId", "el-1")
                        .put("elementType", "figure")
                        .put("data", new JSONObject().put("x", 10)));

        JSONObject answer = conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("UPDATE_ELEMENT").put("canvasId", boardId).put("elementId", "el-1")).get(0);
        assertEquals("ERROR", answer.getString("type"));

        ElementRecord record = storage.elements().find(boardId, "el-1").orElseThrow();
        assertEquals(10, record.data().getInt("x"));
    }

    @Test
    void batchUpdateIsAtomicAndPersistsAllItems() {
        String boardId = createBoard("Пакет");
        for (int i = 0; i < 3; i++) {
            storage.elements().add(boardId, new ElementRecord("e" + i, "figure",
                    new JSONObject().put("x", i), "local", 0));
        }
        var updates = new org.json.JSONArray()
                .put(new JSONObject().put("elementId", "e0").put("data", new JSONObject().put("x", 100)))
                .put(new JSONObject().put("elementId", "e1").put("data", new JSONObject().put("x", 200)));
        JSONObject ok = conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("UPDATE_ELEMENTS").put("canvasId", boardId).put("updates", updates)).get(0);
        assertEquals("ELEMENTS_UPDATED", ok.getString("type"));
        assertEquals(100, storage.elements().find(boardId, "e0").orElseThrow().data().getInt("x"));

        // Один битый элемент в пакете отменяет весь пакет.
        var broken = new org.json.JSONArray()
                .put(new JSONObject().put("elementId", "e2").put("data", new JSONObject().put("x", 300)))
                .put(new JSONObject().put("elementId", "").put("data", new JSONObject().put("x", 400)));
        JSONObject error = conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("UPDATE_ELEMENTS").put("canvasId", boardId).put("updates", broken)).get(0);
        assertEquals("ERROR", error.getString("type"));
        assertEquals(2, storage.elements().find(boardId, "e2").orElseThrow().data().getInt("x"));
    }

    @Test
    void viewerCannotEditButCanJoin() {
        String boardId = createBoard("Просмотр");
        storage.boards().addMember(boardId, "guest", "viewer");

        JSONObject join = conversation("guest", "viewer",
                message("JOIN_CANVAS").put("canvasId", boardId)).get(0);
        assertEquals("JOINED_CANVAS", join.getString("type"));
        assertEquals("viewer", join.getString("role"));

        JSONObject add = conversation("guest", "viewer",
                message("ADD_ELEMENT").put("canvasId", boardId).put("elementId", "x")
                        .put("elementType", "figure").put("data", new JSONObject())).get(0);
        assertEquals("ERROR", add.getString("type"));
        assertTrue(storage.elements().list(boardId).isEmpty());
    }

    @Test
    void assetsRoundTripThroughInProcessServer() {
        byte[] bytes = new byte[]{9, 8, 7};
        String hash = org.example.canvasproject.storage.FileAssetStore.hash(bytes);
        JSONObject stored = conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("ASSET_PUT").put("assetId", hash).put("mime", "image/png")
                        .put("data", java.util.Base64.getEncoder().encodeToString(bytes))).get(0);
        assertEquals("ASSET_STORED", stored.getString("type"));

        JSONObject data = conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("ASSET_GET").put("assetId", hash)).get(0);
        assertEquals("ASSET_DATA", data.getString("type"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), data.getString("data"));
    }

    @Test
    void unknownCommandReturnsError() {
        JSONObject error = new LocalServer(storage, BoardRecord.LOCAL_OWNER, "Локальный", "owner")
                .handle(message("NOPE"));
        assertEquals("ERROR", error.getString("type"));
        assertNotNull(error.optString("message"));
    }

    private String createBoard(String name) {
        return conversation(BoardRecord.LOCAL_OWNER, "owner",
                message("CREATE_CANVAS").put("name", name)).get(0)
                .getJSONObject("canvas").getString("id");
    }
}
