package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {

    @TempDir
    Path temp;

    @Test
    void sqliteRepositoryPersistsAndOrdersBoards() {
        Path db = temp.resolve("slate.db");
        String id;
        try (SqliteBoardRepository repo = new SqliteBoardRepository(db)) {
            LocalBoard first = repo.create("Первая", 1920, 1080);
            id = first.getId();
            first.setElements(new JSONArray()
                    .put(new JSONObject().put("elementId", "e1")
                            .put("elementType", "figure")
                            .put("data", new JSONObject().put("x", 10))));
            repo.save(first);

            LocalBoard second = repo.create("Вторая", 800, 600);
            repo.rename(second.getId(), "Переименованная");

            List<LocalBoard> boards = repo.list();
            assertEquals(2, boards.size());
            assertEquals("Переименованная", boards.get(0).getName());
        }

        try (SqliteBoardRepository reopened = new SqliteBoardRepository(db)) {
            LocalBoard board = reopened.find(id).orElseThrow();
            assertEquals("Первая", board.getName());
            assertEquals(1, board.elementCount());
            assertEquals(10, board.getElements().getJSONObject(0)
                    .getJSONObject("data").getInt("x"));
            assertTrue(reopened.delete(id));
            assertFalse(reopened.delete(id));
        }
    }

    @Test
    void sizeAndNameAreClamped() {
        try (SqliteBoardRepository repo = new SqliteBoardRepository(temp.resolve("s.db"))) {
            LocalBoard board = repo.create("   ", 10, 99999);
            assertEquals("Без названия", board.getName());
            assertEquals(400, board.getWidth());
            assertEquals(4000, board.getHeight());
        }
    }

    @Test
    void slateFileRoundTripsBoardAndElements() throws Exception {
        LocalBoard original = new LocalBoard("b1", "Моя доска", 1600, 900,
                100, 200, new JSONArray()
                .put(new JSONObject().put("elementId", "n1")
                        .put("elementType", "note")
                        .put("data", new JSONObject().put("text", "привет"))));

        Path file = temp.resolve("backup.slate");
        SlateFile.write(original, file);

        LocalBoard restored = SlateFile.read(file);
        assertEquals("b1", restored.getId());
        assertEquals("Моя доска", restored.getName());
        assertEquals(1600, restored.getWidth());
        assertEquals(1, restored.elementCount());
        assertEquals("привет", restored.getElements().getJSONObject(0)
                .getJSONObject("data").getString("text"));
    }

    @Test
    void importNeverOverwritesExistingBoard() throws Exception {
        LocalBoard original = new LocalBoard("same", "Импорт", 800, 600,
                1, 2, new JSONArray());
        Path file = temp.resolve("import.slate");
        SlateFile.write(original, file);

        LocalBoard imported = SlateFile.read(file);
        imported.setId(java.util.UUID.randomUUID().toString());
        assertNotEquals("same", imported.getId());
    }
}
