package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Точка доступа к локальным доскам. Одна база на пользователя; при первом
 * обращении создаётся автоматически. Внутри — общие репозитории: тот же код
 * работает в solo-режиме и во встроенном сервере.
 */
public final class LocalBoardService {

    private static LocalBoardService instance;

    private final Storage storage;

    public LocalBoardService(Storage storage) {
        this.storage = storage;
    }

    public static synchronized LocalBoardService getInstance() {
        if (instance == null) {
            instance = new LocalBoardService(new SqliteStorage(databasePath()));
        }
        return instance;
    }

    public static Path dataDir() {
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg, "canvasproject");
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share", "canvasproject");
    }

    public static Path databasePath() {
        return dataDir().resolve("slate.db");
    }

    public Storage storage() {
        return storage;
    }

    public List<LocalBoard> list() {
        List<LocalBoard> result = new ArrayList<>();
        for (BoardRecord board : storage.boards().listForUser(BoardRecord.LOCAL_OWNER)) {
            result.add(toLocal(board, new JSONArray()));
        }
        return result;
    }

    public Optional<LocalBoard> find(String id) {
        return storage.boards().find(id).map(board -> toLocal(board, elementsJson(id)));
    }

    public LocalBoard create(String name, int width, int height) {
        BoardRecord board = storage.boards().create(name, BoardRecord.LOCAL_OWNER, width, height);
        return toLocal(board, new JSONArray());
    }

    /**
     * Сохраняет содержимое и метаданные доски. Меняются только отличия:
     * обычное автосохранение не переписывает всю доску.
     */
    public void save(LocalBoard board) {
        if (board == null || board.getId() == null) return;
        String id = board.getId();

        storage.boards().rename(id, board.getName());
        storage.boards().resize(id, board.getWidth(), board.getHeight());

        List<ElementRecord> current = storage.elements().list(id);
        Map<String, ElementRecord> existing = new HashMap<>();
        for (ElementRecord record : current) existing.put(record.elementId(), record);

        JSONArray incoming = board.getElements();
        Map<String, ElementRecord> wanted = new HashMap<>();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject item = incoming.optJSONObject(i);
            if (item == null) continue;
            String elementId = item.optString("elementId", "");
            if (elementId.isBlank()) continue;
            wanted.put(elementId, new ElementRecord(
                    elementId,
                    item.optString("elementType", "figure"),
                    item.optJSONObject("data") == null ? new JSONObject() : item.getJSONObject("data"),
                    item.optString("addedBy", ""),
                    item.optLong("updatedAt", System.currentTimeMillis())));
        }

        List<String> deleted = new ArrayList<>();
        for (String elementId : existing.keySet()) {
            if (!wanted.containsKey(elementId)) deleted.add(elementId);
        }
        if (!deleted.isEmpty()) storage.elements().deleteMany(id, deleted);

        List<ElementUpdate> updates = new ArrayList<>();
        List<ElementRecord> added = new ArrayList<>();
        for (ElementRecord record : wanted.values()) {
            ElementRecord old = existing.get(record.elementId());
            if (old == null) {
                added.add(record);
                continue;
            }
            boolean typeChanged = !old.elementType().equals(record.elementType());
            boolean dataChanged = !old.data().similar(record.data());
            if (typeChanged) {
                storage.elements().delete(id, record.elementId());
                added.add(record);
            } else if (dataChanged) {
                updates.add(new ElementUpdate(record.elementId(), record.data()));
            }
        }
        if (!updates.isEmpty()) storage.elements().updateMany(id, updates);
        for (ElementRecord record : added) storage.elements().add(id, record);
    }

    public boolean delete(String id) {
        return storage.boards().delete(id);
    }

    public boolean rename(String id, String newName) {
        return storage.boards().rename(id, newName);
    }

    public void exportBoard(String id, Path target) throws Exception {
        LocalBoard board = find(id)
                .orElseThrow(() -> new IllegalArgumentException("Доска не найдена"));
        SlateFile.write(board, target);
    }

    public LocalBoard importBoard(Path source) throws Exception {
        LocalBoard board = SlateFile.read(source);
        // Импорт всегда создаёт новую запись, чтобы не перезаписать существующую.
        board.setId(java.util.UUID.randomUUID().toString());
        board.setCreatedAt(System.currentTimeMillis());
        board.setUpdatedAt(System.currentTimeMillis());
        BoardRecord created = storage.boards().create(board.getName(),
                BoardRecord.LOCAL_OWNER, board.getWidth(), board.getHeight());
        board.setId(created.id());
        save(board);
        return board;
    }

    private JSONArray elementsJson(String id) {
        JSONArray array = new JSONArray();
        for (ElementRecord record : storage.elements().list(id)) {
            array.put(new JSONObject()
                    .put("elementId", record.elementId())
                    .put("elementType", record.elementType())
                    .put("data", record.data())
                    .put("addedBy", record.addedBy())
                    .put("updatedAt", record.updatedAt()));
        }
        return array;
    }

    private static LocalBoard toLocal(BoardRecord board, JSONArray elements) {
        return new LocalBoard(board.id(), board.name(), board.width(), board.height(),
                board.createdAt(), board.updatedAt(), elements);
    }
}
