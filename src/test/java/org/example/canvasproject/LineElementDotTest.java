package org.example.canvasproject;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineElementDotTest {

    @Test
    void singleClickBecomesVisibleDot() {
        lineElement line = new lineElement(Color.BLACK, 2);
        line.addPoint(40, 50);
        assertEquals(1, line.pointCount());

        line.ensureDot();

        assertEquals(2, line.pointCount());
        assertTrue(Math.abs(line.getPoints().get(2) - 40) < 0.1);
        assertTrue(Math.abs(line.getPoints().get(3) - 50) < 0.1);
    }

    @Test
    void ensureDotDoesNotChangeRealStrokeOrEmptyLine() {
        lineElement stroke = new lineElement(Color.BLACK, 2);
        stroke.addPoint(0, 0);
        stroke.addPoint(10, 10);
        stroke.ensureDot();
        assertEquals(2, stroke.pointCount());

        lineElement empty = new lineElement(Color.BLACK, 2);
        empty.ensureDot();
        assertEquals(0, empty.pointCount());
    }
}
