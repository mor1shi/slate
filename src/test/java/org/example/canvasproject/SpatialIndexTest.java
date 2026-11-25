package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пространственный индекс не должен перебирать все элементы на каждый кадр.
 */
class SpatialIndexTest {

    @Test
    void queryReturnsOnlyNearbyElements() {
        BoardModel model = new BoardModel(null);
        for (int row = 0; row < 50; row++) {
            for (int col = 0; col < 100; col++) {
                double x = col * 100;
                double y = row * 100;
                model.add(new SelectableFake(row + ":" + col, "figure", x, y, 30, 30));
            }
        }
        assertEquals(5000, model.elements().size());

        List<Selectable> nearby = model.queryIn(new BoundingBox(500, 500, 300, 300));

        assertFalse(nearby.isEmpty());
        assertTrue(nearby.size() < 100,
                "индекс должен отсечь далёкие элементы, а не вернуть все 5000");
        assertTrue(nearby.stream().allMatch(el -> {
            var b = el.getSelectBounds();
            return b.getMaxX() >= 500 && b.getMinX() <= 800
                    && b.getMaxY() >= 500 && b.getMinY() <= 800;
        }));
    }

    @Test
    void movingElementUpdatesItsCells() {
        BoardModel model = new BoardModel(null);
        SelectableFake element = new SelectableFake("a", "figure", 10, 10, 20, 20);
        model.add(element);

        element.move(1000, 1000);
        model.refreshSpatialIndex(element);

        assertTrue(model.queryIn(new BoundingBox(5, 5, 40, 40)).isEmpty(),
                "старая ячейка не должна возвращать элемент");
        assertEquals(List.of(element), model.queryIn(new BoundingBox(1000, 1000, 40, 40)));
    }

    @Test
    void queryKeepsModelOrderAfterInsertAndRemove() {
        BoardModel model = new BoardModel(null);
        SelectableFake a = new SelectableFake("a", "figure", 0, 0, 100, 100);
        SelectableFake b = new SelectableFake("b", "figure", 10, 10, 100, 100);
        SelectableFake c = new SelectableFake("c", "figure", 20, 20, 100, 100);
        model.add(a);
        model.add(b);
        model.add(c);

        model.remove(b);
        model.insert(1, b);

        assertEquals(List.of(a, b, c), model.queryIn(new BoundingBox(0, 0, 200, 200)),
                "порядок кандидатов должен совпадать с z-порядком модели");
        assertSame(c, model.findAt(50, 50));
    }

    @Test
    void findAtReturnsTopmostElement() {
        BoardModel model = new BoardModel(null);
        SelectableFake bottom = new SelectableFake("bottom", "figure", 0, 0, 100, 100);
        SelectableFake top = new SelectableFake("top", "figure", 10, 10, 20, 20);
        model.add(bottom);
        model.add(top);

        assertSame(top, model.findAt(15, 15));
        assertSame(bottom, model.findAt(90, 90));
        assertFalse(model.queryIn(new BoundingBox(500, 500, 10, 10)).contains(bottom));
    }
}
