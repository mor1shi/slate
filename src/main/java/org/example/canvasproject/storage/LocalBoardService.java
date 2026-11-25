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
import java.util.UUID;

/**
 * Точка доступа к локальным доскам. Одна база на пользователя; при первом
 * обращении создаётся автоматически. Внутри — общие репозитории: тот же код
 * работает в solo-режиме и во встроенном сервере.
 *
 * <p>Рядом с основной записью хранятся последние {@link SnapshotStore#DEFAULT_LIMIT}
 * снимков доски: перед крупными изменениями (очистка, импорт, восстановление)
 * и не чаще раза в {@link #SNAPSHOT_INTERVAL_MS} при автосохранении.
 */
public final class LocalBoardService {

    /** Как часто автосохранение делает плановый снимок. */
    public static final long SNAPSHOT_INTERVAL_MS = 5 * 60 * 1000L;

    private static LocalBoardService instance;

    private final Storage storage;
    private final SnapshotStore snapshots;

    public LocalBoardService(Storage storage) {
        this(storage, new SnapshotStore(databasePath()));
    }

    public LocalBoardService(Storage storage, SnapshotStore snapshots) {
        this.storage = storage;
        this.snapshots = snapshots;
    }

    public static synchronized LocalBoardService getInstance() {
        if (instance == null) {
            Path database = databasePath();
            instance = new LocalBoardService(new SqliteStorage(database),
                    new SnapshotStore(database));
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

    /**
     * Читает доску. Если основная запись повреждена (SQLite не отвечает),
     * возвращается последний снимок; при пустой основной записи и более
     * свежем непустом снимке содержимое поднимается из него.
     */
    public Optional<LocalBoard> find(String id) {
        try {
            Optional<LocalBoard> loaded = load(id);
            return loaded.map(this::recoverIfNewer);
        } catch (RuntimeException e) {
            Optional<SnapshotStore.Snapshot> latest = snapshots.latest(id);
            if (latest.isEmpty()) throw e;
            LocalBoard recovered = fromSnapshot(latest.get(), id);
            try {
                save(recovered);
            } catch (RuntimeException ignored) {
                // Основная запись может быть физически недоступна: отдаём снимок.
            }
            return Optional.of(recovered);
        }
    }

    private Optional<LocalBoard> load(String id) {
        return storage.boards().find(id).map(board -> {
            JSONArray elements = elementsJson(id);
            List<ElementUpdate> migrated = new ArrayList<>();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject item = elements.optJSONObject(i);
                if (item == null) continue;
                JSONObject data = item.optJSONObject("data");
                if (AssetMigration.migrateElementData(data, storage.assets())) {
                    migrated.add(new ElementUpdate(item.optString("elementId"), data));
                }
            }
            if (!migrated.isEmpty()) {
                storage.elements().updateMany(id, migrated);
            }
            return toLocal(board, elements);
        });
    }

    /**
     * Незавершённая сессия или потеря содержимого: основная запись пуста, а
     * снимок новее неё и содержит элементы. Пустая доска, очищенная
     * пользователем, не восстанавливается: её `updated_at` новее снимка.
     */
    private LocalBoard recoverIfNewer(LocalBoard board) {
        if (board.elementCount() > 0) return board;
        Optional<SnapshotStore.Snapshot> latest = snapshots.latest(board.getId());
        if (latest.isEmpty()) return board;
        SnapshotStore.Snapshot snapshot = latest.get();
        if (snapshot.createdAt() <= board.getUpdatedAt()) return board;
        LocalBoard restored = fromSnapshot(snapshot, board.getId());
        if (restored.elementCount() == 0) return board;
        try {
            save(restored);
        } catch (RuntimeException e) {
            return board;
        }
        return restored;
    }

    public LocalBoard create(String name, int width, int height) {
        BoardRecord board = storage.boards().create(name, BoardRecord.LOCAL_OWNER, width, height);
        return toLocal(board, new JSONArray());
    }

    /**
     * Первый запуск: если локальное хранилище пусто, создаётся одна доска.
     * Флаг хранится в SQLite, поэтому после удаления всех досок она больше
     * не появляется сама.
     */
    public Optional<LocalBoard> ensureWelcomeBoard(String name) {
        if (snapshots.hasFlag(SnapshotStore.FLAG_WELCOME_BOARD)) return Optional.empty();
        boolean empty = storage.boards().listForUser(BoardRecord.LOCAL_OWNER).isEmpty();
        snapshots.setFlag(SnapshotStore.FLAG_WELCOME_BOARD);
        if (!empty) return Optional.empty();
        return Optional.of(create(name, 1920, 1080));
    }

    /**
     * Сохраняет содержимое и метаданные доски. Меняются только отличия:
     * обычное автосохранение не переписывает всю доску.
     */
    public void save(LocalBoard board) {
        if (board == null || board.getId() == null) return;
        String id = board.getId();

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

        maybeSnapshotBeforeSave(id, existing.size(), wanted.size());

        storage.boards().rename(id, board.getName());
        storage.boards().resize(id, board.getWidth(), board.getHeight());

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

    /** Снимок делается до метаданных, чтобы его время было старше `updated_at`. */
    private void maybeSnapshotBeforeSave(String id, int existingCount, int incomingCount) {
        boolean destructive = existingCount >= 4 && incomingCount < existingCount / 2;
        List<SnapshotStore.Snapshot> all;
        try {
            all = snapshots.list(id);
        } catch (RuntimeException e) {
            return;
        }
        boolean stale = all.isEmpty()
                || System.currentTimeMillis() - all.get(0).createdAt() >= SNAPSHOT_INTERVAL_MS;
        if (!destructive && !stale) return;
        try {
            BoardRecord record = storage.boards().find(id).orElse(null);
            if (record == null) return;
            LocalBoard currentBoard = toLocal(record, elementsJson(id));
            snapshotNow(currentBoard, destructive ? "before-clear" : "auto");
        } catch (RuntimeException ignored) {
            // Снимок не должен мешать сохранению доски.
        }
    }

    public boolean delete(String id) {
        boolean deleted = storage.boards().delete(id);
        if (deleted) snapshots.deleteFor(id);
        return deleted;
    }

    public boolean rename(String id, String newName) {
        return storage.boards().rename(id, newName);
    }

    public void exportBoard(String id, Path target) throws Exception {
        LocalBoard board = find(id)
                .orElseThrow(() -> new IllegalArgumentException("Доска не найдена"));
        SlateFile.write(board, storage.assets(), target);
    }

    /**
     * Импорт всегда создаёт новую запись, чтобы не перезаписать существующую.
     * Ассеты сначала кладутся в локальное хранилище, потом пишется доска:
     * ошибка чтения архива не оставляет полусохранённых данных.
     */
    public LocalBoard importBoard(Path source) throws Exception {
        SlateFile.Contents contents = SlateFile.read(source);
        return importContents(contents);
    }

    private LocalBoard importContents(SlateFile.Contents contents) {
        LocalBoard board = contents.board();
        Map<String, String> remapped = new HashMap<>();
        for (Map.Entry<String, byte[]> asset : contents.assets().entrySet()) {
            String hash = storage.assets().put(asset.getValue());
            if (!hash.equals(asset.getKey())) remapped.put(asset.getKey(), hash);
        }
        JSONArray elements = board.getElements();
        AssetMigration.migrateAll(elements, storage.assets());
        if (!remapped.isEmpty()) {
            for (int i = 0; i < elements.length(); i++) {
                JSONObject data = elements.optJSONObject(i) == null
                        ? null : elements.optJSONObject(i).optJSONObject("data");
                if (data == null) continue;
                String mapped = remapped.get(data.optString("assetId", ""));
                if (mapped != null) data.put("assetId", mapped);
            }
        }

        board.setId(UUID.randomUUID().toString());
        board.setCreatedAt(System.currentTimeMillis());
        board.setUpdatedAt(System.currentTimeMillis());
        BoardRecord created = storage.boards().create(board.getName(),
                BoardRecord.LOCAL_OWNER, board.getWidth(), board.getHeight());
        board.setId(created.id());
        save(board);
        snapshotNow(board, "import");
        return find(created.id()).orElse(board);
    }

    /** Полный снимок переданного состояния доски. */
    public SnapshotStore.Snapshot snapshotNow(LocalBoard board, String reason) {
        if (board == null || board.getId() == null) {
            throw new IllegalArgumentException("Нет доски для снимка");
        }
        return snapshots.save(board.getId(), reason,
                SlateFile.toJson(board).toString(), SnapshotStore.DEFAULT_LIMIT);
    }

    public List<SnapshotStore.Snapshot> snapshots(String boardId) {
        return snapshots.list(boardId);
    }

    /** Восстанавливает снимок в основную запись, сохранив текущее состояние. */
    public LocalBoard restoreSnapshot(String boardId, long snapshotId) {
        SnapshotStore.Snapshot target = snapshots.list(boardId).stream()
                .filter(s -> s.id() == snapshotId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Снимок не найден"));
        load(boardId).ifPresent(current -> snapshotNow(current, "before-restore"));
        LocalBoard restored = fromSnapshot(target, boardId);
        save(restored);
        return restored;
    }

    private static LocalBoard fromSnapshot(SnapshotStore.Snapshot snapshot, String boardId) {
        try {
            LocalBoard board = SlateFile.fromJson(new JSONObject(snapshot.payload()));
            board.setId(boardId);
            return board;
        } catch (Exception e) {
            throw new StorageException("Повреждён снимок доски " + boardId, e);
        }
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
