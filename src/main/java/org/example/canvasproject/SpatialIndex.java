package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Равномерная сетка для быстрого поиска элементов по области.
 *
 * <p>Один элемент попадает во все ячейки, которые пересекает его рамка. На
 * доске с тысячами элементов запрос рамки смотрит только ближайшие ячейки,
 * а не весь список. Индекс хранит ссылки на элементы, поэтому память остаётся
 * линейной.
 */
public final class SpatialIndex {

    private record Cell(int x, int y) {}

    private final double cellSize;
    private final Map<Cell, List<Selectable>> cells = new HashMap<>();
    private final IdentityHashMap<Selectable, List<Cell>> placement = new IdentityHashMap<>();

    public SpatialIndex(double cellSize) {
        if (cellSize <= 0) throw new IllegalArgumentException("cellSize должен быть > 0");
        this.cellSize = cellSize;
    }

    public double getCellSize() { return cellSize; }

    public void clear() {
        cells.clear();
        placement.clear();
    }

    public void add(Selectable element) {
        if (element == null || placement.containsKey(element)) return;
        Bounds b = element.getSelectBounds();
        if (b == null) return;
        List<Cell> occupied = cover(b);
        placement.put(element, occupied);
        for (Cell cell : occupied) {
            cells.computeIfAbsent(cell, k -> new ArrayList<>()).add(element);
        }
    }

    public void remove(Selectable element) {
        if (element == null) return;
        List<Cell> occupied = placement.remove(element);
        if (occupied == null) return;
        for (Cell cell : occupied) {
            List<Selectable> list = cells.get(cell);
            if (list == null) continue;
            list.remove(element);
            if (list.isEmpty()) cells.remove(cell);
        }
    }

    /** Пересчитывает ячейки после перемещения или изменения размера. */
    public void update(Selectable element) {
        remove(element);
        add(element);
    }

    public List<Selectable> query(Bounds area) {
        if (area == null) return List.of();
        Set<Selectable> found = new LinkedHashSet<>();
        for (Cell cell : cover(area)) {
            List<Selectable> list = cells.get(cell);
            if (list == null) continue;
            for (Selectable element : list) {
                Bounds b = element.getSelectBounds();
                if (b != null && SelectionGeometry.intersects(area, b)) {
                    found.add(element);
                }
            }
        }
        return new ArrayList<>(found);
    }

    public List<Selectable> query(Point2D point) {
        if (point == null) return List.of();
        Cell cell = cellOf(point.getX(), point.getY());
        List<Selectable> list = cells.get(cell);
        if (list == null) return List.of();
        List<Selectable> result = new ArrayList<>();
        for (Selectable element : list) {
            Bounds b = element.getSelectBounds();
            if (b != null && b.contains(point)) result.add(element);
        }
        return result;
    }

    private List<Cell> cover(Bounds b) {
        int minX = cellCoord(b.getMinX());
        int maxX = cellCoord(b.getMaxX());
        int minY = cellCoord(b.getMinY());
        int maxY = cellCoord(b.getMaxY());
        List<Cell> result = new ArrayList<>((maxX - minX + 1) * (maxY - minY + 1));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                result.add(new Cell(x, y));
            }
        }
        return result;
    }

    private Cell cellOf(double x, double y) {
        return new Cell(cellCoord(x), cellCoord(y));
    }

    private int cellCoord(double value) {
        return (int) Math.floor(value / cellSize);
    }
}
