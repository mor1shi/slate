package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Снимки доски в SQLite: последние версии сохраняются, старые удаляются,
 * восстановление возвращает элементы, а повреждённая основная запись
 * поднимается из последнего снимка.
 */
class BoardSnapshotTest {

    @TempDir
    Path temp;

    private Path dbFile;
    private SqliteStorage storage;
    private SnapshotStore snapshots;
    private LocalBoardService service;

    @BeforeEach
    void setUp() {
        dbFile = temp.resolve("slate.db");
        storage = new SqliteStorage(dbFile);
        snapshots = new SnapshotStore(dbFile);
        service = new LocalBoardService(storage, snapshots);
    }

    @AfterEach
    void tearDown() {
        snapshots.close();
        storage.close();
    }

    @Test
    void snapshotsAreStoredNewestFirstAndPruned() {
        LocalBoard board = service.create("Версии", 800, 600);
        for (int i = 0; i < SnapshotStore.DEFAULT_LIMIT + 3; i++) {
            board.setElements(new JSONArray().put(note("e-" + i, "текст " + i)));
            service.save(board);
            service.snapshotNow(board, "manual");
        }

        List<SnapshotStore.Snapshot> kept = service.snapshots(board.getId());
        assertEquals(SnapshotStore.DEFAULT_LIMIT, kept.size());
        assertTrue(kept.get(0).id() > kept.get(kept.size() - 1).id());
        assertFalse(kept.stream().anyMatch(s -> s.payload().contains("текст 0")));
        assertTrue(kept.stream().anyMatch(s -> s.payload().contains("текст 12")));
    }

    @Test
    void restoreSnapshotBringsElementsBack() {
        LocalBoard board = service.create("Откат", 800, 600);
        board.setElements(new JSONArray().put(note("keep", "важное")));
        service.save(board);
        service.snapshotNow(board, "manual");
        long snapshotId = service.snapshots(board.getId()).get(0).id();

        board.setElements(new JSONArray());
        service.save(board);
        assertEquals(0, service.find(board.getId()).orElseThrow().elementCount());

        LocalBoard restored = service.restoreSnapshot(board.getId(), snapshotId);
        assertEquals(1, restored.elementCount());
        assertEquals("важное", service.find(board.getId()).orElseThrow()
                .getElements().getJSONObject(0).getJSONObject("data").getString("text"));
    }

    @Test
    void latestSnapshotSurvivesDamagedMainRecord() {
        LocalBoard board = service.create("Авария", 800, 600);
        board.setElements(new JSONArray().put(note("saved", "до аварии")));
        service.save(board);
        service.snapshotNow(board, "auto");

        storage.close();

        LocalBoard recovered = service.find(board.getId()).orElseThrow();
        assertEquals(1, recovered.elementCount());
        assertEquals("до аварии", recovered.getElements().getJSONObject(0)
                .getJSONObject("data").getString("text"));
        // Пересоздаём соединение, чтобы tearDown не падал на уже закрытом.
        storage = new SqliteStorage(dbFile);
    }

    @Test
    void destructiveSaveCreatesSnapshotBeforeChange() {
        LocalBoard board = service.create("Очистка", 800, 600);
        JSONArray six = new JSONArray();
        for (int i = 0; i < 6; i++) {
            six.put(note("e-" + i, "t"));
        }
        board.setElements(six);
        service.save(board);
        service.snapshotNow(board, "manual");
        int before = service.snapshots(board.getId()).size();

        board.setElements(new JSONArray());
        service.save(board);

        assertTrue(service.snapshots(board.getId()).size() > before);
        assertTrue(service.snapshots(board.getId()).stream()
                .anyMatch(s -> s.reason().equals("before-clear")));
    }

    @Test
    void welcomeBoardIsCreatedOnlyOnceOnEmptyStorage() {
        LocalBoard created = service.ensureWelcomeBoard("Без названия").orElseThrow();
        assertEquals("Без названия", created.getName());
        assertEquals(1, service.list().size());

        assertEquals(0, service.ensureWelcomeBoard("Без названия").stream().count());
        assertEquals(1, service.list().size());

        service.delete(created.getId());
        assertEquals(0, service.ensureWelcomeBoard("Без названия").stream().count());
        assertEquals(0, service.list().size());
    }

    @Test
    void snapshotsOfDeletedBoardDoNotLeak() {
        LocalBoard board = service.create("Удаляемая", 800, 600);
        service.snapshotNow(board, "manual");
        assertNotEquals(0, service.snapshots(board.getId()).size());
        service.delete(board.getId());
        assertTrue(service.snapshots(board.getId()).isEmpty());
    }

    private static JSONObject note(String id, String text) {
        return new JSONObject()
                .put("elementId", id)
                .put("elementType", "note")
                .put("data", new JSONObject().put("text", text));
    }
}
