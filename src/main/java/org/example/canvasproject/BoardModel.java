package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Единая модель доски: один список ВСЕХ элементов + ссылка на группу сцен-графа.
 *
 * <p>Порядок списка — это z-порядок: последний элемент рисуется сверху.
 * Для поиска по рамке используется {@link SpatialIndex}; перебирать весь
 * список на каждое движение мыши не нужно.
 */
public class BoardModel {

    private static final double INDEX_CELL_SIZE = 256;

    private final Group group;
    private final List<Selectable> elements = new ArrayList<>();
    private final SpatialIndex spatialIndex = new SpatialIndex(INDEX_CELL_SIZE);
    /** Позиция элемента в модели для восстановления z-порядка без перебора. */
    private final java.util.IdentityHashMap<Selectable, Integer> order = new java.util.IdentityHashMap<>();

    public BoardModel(Group group) {
        this.group = group;
    }

    public void add(Selectable el) {
        if (el == null || elements.contains(el)) return;
        elements.add(el);
        order.put(el, elements.size() - 1);
        spatialIndex.add(el);
        if (group != null && el.getNode() != null && !group.getChildren().contains(el.getNode())) {
            group.getChildren().add(el.getNode());
        }
    }

    /** Вставляет элемент на прежнее место — нужно для точного Undo удаления. */
    public void insert(int index, Selectable el) {
        if (el == null || elements.contains(el)) return;
        int target = Math.max(0, Math.min(index, elements.size()));
        elements.add(target, el);
        reindexOrder();
        spatialIndex.add(el);

        if (group != null && el.getNode() != null && !group.getChildren().contains(el.getNode())) {
            Node next = target < elements.size() - 1 ? elements.get(target + 1).getNode() : null;
            int nodeIndex = next == null ? -1 : group.getChildren().indexOf(next);
            if (nodeIndex >= 0) group.getChildren().add(nodeIndex, el.getNode());
            else group.getChildren().add(el.getNode());
        }
    }

    public void remove(Selectable el) {
        elements.remove(el);
        order.remove(el);
        reindexOrder();
        spatialIndex.remove(el);
        if (el != null && el.getNode() != null && group != null) {
            group.getChildren().remove(el.getNode());
        }
    }

    public int indexOf(Selectable el) {
        return elements.indexOf(el);
    }

    public List<Selectable> elements() {
        return elements;
    }

    public List<Selectable> queryIn(Bounds area) {
        if (area == null) return List.of();
        // Индекс отдаёт кандидатов; порядок восстанавливаем по сохранённым
        // позициям, не перебирая весь список доски.
        List<Selectable> candidates = spatialIndex.query(area);
        if (candidates.isEmpty()) return List.of();
        candidates.sort(java.util.Comparator.comparingInt(
                el -> order.getOrDefault(el, Integer.MAX_VALUE)));
        return candidates;
    }

    public Selectable findAt(double x, double y) {
        List<Selectable> candidates = spatialIndex.query(new Point2D(x, y));
        if (candidates.isEmpty()) return null;
        Selectable top = null;
        int topOrder = -1;
        for (Selectable el : candidates) {
            int position = order.getOrDefault(el, -1);
            if (position > topOrder && el.contains(x, y)) {
                top = el;
                topOrder = position;
            }
        }
        return top;
    }

    private void reindexOrder() {
        order.clear();
        for (int i = 0; i < elements.size(); i++) order.put(elements.get(i), i);
    }

    /** Полный пересчёт: используется после загрузки доски и массовых правок. */
    public void refreshSpatialIndex() {
        spatialIndex.clear();
        for (Selectable el : elements) spatialIndex.add(el);
    }

    public void refreshSpatialIndex(Selectable el) {
        spatialIndex.update(el);
    }

    public SpatialIndex getSpatialIndex() { return spatialIndex; }
}
