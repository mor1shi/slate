package org.example.canvasproject.storage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Точка доступа к локальным доскам. Одна база на пользователя; при первом
 * обращении создаётся автоматически.
 */
public final class LocalBoardService {

    private static LocalBoardService instance;

    private final BoardRepository repository;

    public LocalBoardService(BoardRepository repository) {
        this.repository = repository;
    }

    public static synchronized LocalBoardService getInstance() {
        if (instance == null) {
            instance = new LocalBoardService(new SqliteBoardRepository(databasePath()));
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

    public List<LocalBoard> list() {
        return repository.list();
    }

    public java.util.Optional<LocalBoard> find(String id) {
        return repository.find(id);
    }

    public LocalBoard create(String name, int width, int height) {
        return repository.create(name, width, height);
    }

    public void save(LocalBoard board) {
        repository.save(board);
    }

    public boolean delete(String id) {
        return repository.delete(id);
    }

    public boolean rename(String id, String newName) {
        return repository.rename(id, newName);
    }

    public BoardRepository repository() {
        return repository;
    }

    public void exportBoard(String id, Path target) throws Exception {
        LocalBoard board = repository.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Доска не найдена"));
        SlateFile.write(board, target);
    }

    public LocalBoard importBoard(Path source) throws Exception {
        LocalBoard board = SlateFile.read(source);
        // Импорт всегда создаёт новую запись, чтобы не перезаписать существующую.
        board.setId(java.util.UUID.randomUUID().toString());
        board.setCreatedAt(System.currentTimeMillis());
        board.setUpdatedAt(System.currentTimeMillis());
        repository.save(board);
        return board;
    }
}
