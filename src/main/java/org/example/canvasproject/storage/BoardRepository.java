package org.example.canvasproject.storage;

import java.util.List;
import java.util.Optional;

/** Хранилище досок. Реализации: SQLite (по умолчанию) и будущий Mongo-адаптер. */
public interface BoardRepository extends AutoCloseable {

    List<LocalBoard> list();

    Optional<LocalBoard> find(String id);

    LocalBoard create(String name, int width, int height);

    void save(LocalBoard board);

    boolean delete(String id);

    boolean rename(String id, String newName);

    @Override
    void close();
}
