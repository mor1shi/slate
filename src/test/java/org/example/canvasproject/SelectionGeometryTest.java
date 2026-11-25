package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Геометрия рамки выделения: оба режима, все типы элементов, обратное
 * направление, нулевая ширина и преобразование координат холста.
 */
class SelectionGeometryTest {

    private static Bounds frame(double x, double y, double w, double h) {
        return new BoundingBox(x, y, w, h);
    }

    @Test
    void everyFigureTypeContainsAndTouchesByItsBounds() {
        for (String type : List.of("rect", "circle", "triangle", "star", "arrow")) {
            figureElement figure = new figureElement(type, 100, 100, 50, 40, Color.BLUE);

            Bounds inside = frame(90, 90, 70, 60);
            assertTrue(SelectionGeometry.hits(figure, inside, true),
                    type + ": элемент целиком внутри рамки");
            assertTrue(SelectionGeometry.hits(figure, inside, false),
                    type + ": рамка касается элемента");

            Bounds overlap = frame(100, 100, 10, 10);
            assertFalse(SelectionGeometry.hits(figure, overlap, true),
                    type + ": режим «целиком» не берёт частичное касание");
            assertTrue(SelectionGeometry.hits(figure, overlap, false),
                    type + ": режим «касание» берёт частичное касание");

            assertFalse(SelectionGeometry.hits(figure, frame(500, 500, 20, 20), false),
                    type + ": далёкая рамка ничего не берёт");
        }
    }

    @Test
    void lineUsesPointsNotOnlyItsPaddedBoundingBox() {
        lineElement line = new lineElement(Color.BLACK, 2);
        line.addPoint(0, 0);
        line.addPoint(100, 100);

        assertTrue(SelectionGeometry.hits(line, frame(-1, -1, 102, 102), true),
                "линия целиком внутри большой рамки");
        assertFalse(SelectionGeometry.hits(line, frame(0, 0, 50, 50), true),
                "половина линии не считается целой");
        assertTrue(SelectionGeometry.hits(line, frame(0, 0, 50, 50), false),
                "рамка пересекает линию");
        assertFalse(SelectionGeometry.hits(line, frame(200, 200, 20, 20), false),
                "рамка рядом с линией не должна её касаться");
    }

    @Test
    void lineTouchesFrameWhenSegmentCrossesItWithoutVertexInside() {
        lineElement line = new lineElement(Color.BLACK, 2);
        line.addPoint(0, 50);
        line.addPoint(100, 50);

        // Рамка стоит посередине отрезка: ни одна точка не внутри, но отрезок пересекает её.
        assertTrue(SelectionGeometry.hits(line, frame(40, 40, 20, 20), false));
        assertFalse(SelectionGeometry.hits(line, frame(40, 40, 20, 20), true));
    }

    @Test
    void textImageAndNoteUseTheirSelectBounds() {
        for (String type : List.of("text", "image", "note")) {
            SelectableFake element = new SelectableFake(type, type, 10, 20, 100, 80);
            assertTrue(SelectionGeometry.hits(element, frame(0, 0, 200, 200), true),
                    type + ": целиком");
            assertTrue(SelectionGeometry.hits(element, frame(10, 20, 1, 1), false),
                    type + ": касание");
            assertFalse(SelectionGeometry.hits(element, frame(10, 20, 1, 1), true),
                    type + ": не целиком");
        }
    }

    @Test
    void reverseDirectionSelectsTouchModeAndNormalizesFrame() {
        assertTrue(SelectionGeometry.isContainMode(100, 200),
                "слева направо — целиком внутри");
        assertFalse(SelectionGeometry.isContainMode(200, 100),
                "справа налево — касание");

        Bounds reversed = SelectionGeometry.normalize(300, 250, 100, 50);
        assertEquals(100, reversed.getMinX(), 1e-9);
        assertEquals(50, reversed.getMinY(), 1e-9);
        assertEquals(200, reversed.getWidth(), 1e-9);
        assertEquals(200, reversed.getHeight(), 1e-9);
    }

    @Test
    void zeroWidthFrameStillBehavesLikeTouchLine() {
        SelectableFake element = new SelectableFake("a", "figure", 100, 100, 50, 50);
        Bounds zero = SelectionGeometry.normalize(120, 0, 120, 400);

        assertEquals(0, zero.getWidth(), 1e-9);
        assertTrue(SelectionGeometry.hits(element, zero, false),
                "нулевая ширина — это линия, касание по границе");
        assertFalse(SelectionGeometry.hits(element, zero, true));
    }

    @Test
    void sceneCoordinatesAreConvertedToCanvasCoordinates() {
        Group canvasGroup = new Group();
        canvasGroup.getTransforms().add(new Scale(2, 2, 0, 0));
        canvasGroup.setTranslateX(30);
        canvasGroup.setTranslateY(40);

        Point2D scene = canvasGroup.localToScene(100, 100);
        Point2D canvas = SelectionGeometry.toCanvas(canvasGroup, scene.getX(), scene.getY());

        assertEquals(100, canvas.getX(), 1e-9);
        assertEquals(100, canvas.getY(), 1e-9);
    }

    @Test
    void selectionQueriesSpatialIndexAndKeepsModelOrder() {
        BoardModel model = new BoardModel(null);
        SelectableFake a = new SelectableFake("a", "figure", 10, 10, 20, 20);
        SelectableFake b = new SelectableFake("b", "figure", 50, 50, 20, 20);
        SelectableFake c = new SelectableFake("c", "figure", 500, 500, 20, 20);
        model.add(a);
        model.add(b);
        model.add(c);

        List<Selectable> found = SelectionGeometry.select(model, frame(0, 0, 100, 100), false);

        assertEquals(List.of("a", "b"),
                found.stream().map(Selectable::getElementId).collect(Collectors.toList()));
    }

    @Test
    void unionBoundsCoversWholeSelection() {
        SelectableFake a = new SelectableFake("a", "figure", 10, 20, 30, 40);
        SelectableFake b = new SelectableFake("b", "figure", 100, 200, 50, 60);

        Bounds union = SelectionGeometry.unionBounds(Set.of(a, b));

        assertEquals(10, union.getMinX(), 1e-9);
        assertEquals(20, union.getMinY(), 1e-9);
        assertEquals(140, union.getWidth(), 1e-9);
        assertEquals(240, union.getHeight(), 1e-9);
    }
}
