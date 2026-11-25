package org.example.canvasproject.storage;

import org.json.JSONObject;

import java.util.List;
import java.util.Optional;

/**
 * Элементы доски.
 *
 * <p>Пакетные операции обязаны быть атомарными: при ошибке проверки или записи
 * не применяется ни одно изменение. Они нужны для группового перетаскивания,
 * удаления и Undo/Redo.
 */
public interface ElementRepository {

    List<ElementRecord> list(String boardId);

    Optional<ElementRecord> find(String boardId, String elementId);

    /** Идемпотентно: элемент с тем же id заменяется, а не дублируется. */
    void add(String boardId, ElementRecord element);

    boolean update(String boardId, String elementId, JSONObject data);

    boolean delete(String boardId, String elementId);

    /** Атомарно обновляет все перечисленные элементы. */
    void updateMany(String boardId, List<ElementUpdate> updates);

    /** Атомарно удаляет все перечисленные элементы; возвращает число удалённых. */
    int deleteMany(String boardId, List<String> elementIds);

    /** Атомарно заменяет весь набор элементов доски (автосохранение, импорт). */
    void replaceAll(String boardId, List<ElementRecord> elements);

    int count(String boardId);
}
