package org.example.canvasproject.service.repository;

import org.bson.Document;
import org.example.canvasproject.models.CanvasDocument;
import org.example.canvasproject.models.CanvasElement;
import org.example.canvasproject.service.MongoDBService;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.ElementRepository;
import org.example.canvasproject.storage.ElementUpdate;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mongo-адаптер ElementRepository. Пакетные операции пересобирают массив
 * элементов и записывают его одним updateOne — читатель не увидит половину
 * изменений.
 */
final class MongoElementRepository implements ElementRepository {

    private final MongoDBService mongo;

    MongoElementRepository(MongoDBService mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<ElementRecord> list(String boardId) {
        CanvasDocument board = mongo.findCanvasById(boardId);
        if (board == null || board.getElements() == null) return List.of();
        List<ElementRecord> result = new ArrayList<>();
        for (CanvasElement element : board.getElements()) result.add(toRecord(element));
        return result;
    }

    @Override
    public Optional<ElementRecord> find(String boardId, String elementId) {
        return list(boardId).stream()
                .filter(e -> e.elementId().equals(elementId))
                .findFirst();
    }

    @Override
    public void add(String boardId, ElementRecord element) {
        requireId(element.elementId());
        mongo.addElementToCanvas(boardId, toElement(element));
    }

    @Override
    public boolean update(String boardId, String elementId, JSONObject data) {
        if (elementId == null || elementId.isBlank() || data == null) return false;
        if (find(boardId, elementId).isEmpty()) return false;
        mongo.updateElementInCanvas(boardId, elementId, Document.parse(data.toString()));
        return true;
    }

    @Override
    public boolean delete(String boardId, String elementId) {
        if (elementId == null || elementId.isBlank()) return false;
        if (find(boardId, elementId).isEmpty()) return false;
        mongo.removeElementFromCanvas(boardId, elementId);
        return true;
    }

    @Override
    public void updateMany(String boardId, List<ElementUpdate> updates) {
        if (updates == null) throw new IllegalArgumentException("Нет пакета обновлений");
        for (ElementUpdate update : updates) {
            if (update == null) throw new IllegalArgumentException("Пустое обновление");
            requireId(update.elementId());
        }
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (ElementUpdate update : updates) byId.put(update.elementId(), update.data());

        List<ElementRecord> merged = new ArrayList<>();
        for (ElementRecord current : list(boardId)) {
            JSONObject replacement = byId.get(current.elementId());
            merged.add(replacement == null ? current
                    : new ElementRecord(current.elementId(), current.elementType(),
                            replacement, current.addedBy(), System.currentTimeMillis()));
        }
        mongo.replaceElements(boardId, toElements(merged));
    }

    @Override
    public int deleteMany(String boardId, List<String> elementIds) {
        if (elementIds == null) throw new IllegalArgumentException("Нет списка элементов");
        Set<String> ids = new HashSet<>();
        for (String id : elementIds) {
            requireId(id);
            ids.add(id);
        }
        List<ElementRecord> kept = new ArrayList<>();
        int removed = 0;
        for (ElementRecord element : list(boardId)) {
            if (ids.contains(element.elementId())) removed++;
            else kept.add(element);
        }
        if (removed > 0) mongo.replaceElements(boardId, toElements(kept));
        return removed;
    }

    @Override
    public void replaceAll(String boardId, List<ElementRecord> elements) {
        if (elements == null) throw new IllegalArgumentException("Нет списка элементов");
        Set<String> seen = new HashSet<>();
        for (ElementRecord element : elements) {
            if (element == null) throw new IllegalArgumentException("Пустой элемент");
            requireId(element.elementId());
            if (!seen.add(element.elementId())) {
                throw new IllegalArgumentException("Повтор id: " + element.elementId());
            }
        }
        mongo.replaceElements(boardId, toElements(elements));
    }

    @Override
    public int count(String boardId) {
        return list(boardId).size();
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Пустой id элемента");
        }
    }

    private static List<CanvasElement> toElements(List<ElementRecord> records) {
        List<CanvasElement> result = new ArrayList<>();
        for (ElementRecord record : records) result.add(toElement(record));
        return result;
    }

    private static CanvasElement toElement(ElementRecord record) {
        CanvasElement element = new CanvasElement(record.elementId(), record.elementType(),
                Document.parse(record.data().toString()), record.addedBy());
        element.setUpdatedAt(record.updatedAt());
        return element;
    }

    private static ElementRecord toRecord(CanvasElement element) {
        JSONObject data = element.getData() == null
                ? new JSONObject() : new JSONObject(element.getData().toJson());
        return new ElementRecord(element.getElementId(), element.getType(), data,
                element.getAddedBy(), element.getUpdatedAt());
    }
}
