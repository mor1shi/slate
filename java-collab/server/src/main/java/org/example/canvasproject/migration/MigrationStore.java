package org.example.canvasproject.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Хранилище, участвующее в переносе. Одна реализация умеет и читать, и писать,
 * поэтому направления Mongo → SQLite и SQLite → Mongo используют один код.
 *
 * <p>Запись обязана быть идемпотентной: повторный запуск обновляет записи по
 * устойчивым id, а не создаёт дубли. Для досок набор элементов и участников
 * зеркалится: лишние элементы целевой доски удаляются.
 */
public interface MigrationStore extends AutoCloseable {

    /** Описание для отчёта; не должно содержать паролей и ключей. */
    String description();

    MigrationData read();

    WriteResult write(MigrationData data);

    /** Резервная копия данных назначения. Вызывается до записи. */
    default List<Path> backup(Path directory) throws IOException {
        return List.of();
    }

    default java.util.Map<String, Integer> counts() {
        return read().counts();
    }

    @Override
    default void close() {
    }
}
