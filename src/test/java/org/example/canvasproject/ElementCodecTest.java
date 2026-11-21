package org.example.canvasproject;

import javafx.scene.paint.Color;
import org.example.canvasproject.collab.SyncManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховочные тесты формата элементов, которыми обмениваются клиент и сервер.
 *
 * Пока кодек живёт приватно внутри SyncManager, тест вызывает его через
 * рефлексию. При выносе кодека в отдельный класс тест нужно переписать на
 * публичный API (см. docs/AUDIT.md, технический долг).
 */
class ElementCodecTest {

    private static Method serializeElement;
    private static Method deserializeElement;
    private static SyncManager manager;

    @BeforeAll
    static void resolveCodec() throws Exception {
        manager = new SyncManager(null, null);
        serializeElement = SyncManager.class
                .getDeclaredMethod("serializeElement", Selectable.class);
        deserializeElement = SyncManager.class
                .getDeclaredMethod("deserializeElement", String.class, String.class, JSONObject.class);
        serializeElement.setAccessible(true);
        deserializeElement.setAccessible(true);
    }

    private JSONObject serialize(Selectable element) throws Exception {
        return (JSONObject) serializeElement.invoke(manager, element);
    }

    private Selectable deserialize(String id, String type, JSONObject data) throws Exception {
        return (Selectable) deserializeElement.invoke(manager, id, type, data);
    }

    @Test
    void figureSurvivesJsonRoundTrip() throws Exception {
        figureElement original = new figureElement(
                "triangle", 10, 20, 120, 90, Color.web("#FF5500"));

        JSONObject data = serialize(original);

        assertEquals(10.0, data.getDouble("x"), 1e-9);
        assertEquals(20.0, data.getDouble("y"), 1e-9);
        assertEquals(120.0, data.getDouble("width"), 1e-9);
        assertEquals(90.0, data.getDouble("height"), 1e-9);
        assertEquals("triangle", data.getString("figureType"));
        assertEquals("#FF5500", data.getString("color"));

        figureElement restored = (figureElement) deserialize("el-1", "figure", data);
        assertNotNull(restored);
        assertEquals("triangle", restored.getFigureType());
        assertEquals(10.0, restored.getX(), 1e-9);
        assertEquals(20.0, restored.getY(), 1e-9);
        assertEquals(120.0, restored.getWidth(), 1e-9);
        assertEquals(90.0, restored.getHeight(), 1e-9);
        assertEquals(Color.web("#FF5500"), restored.getFillColor());
    }

    @Test
    void lineKeepsPointsLayoutAndOpacity() throws Exception {
        lineElement original = new lineElement(Color.color(0.2, 0.4, 0.6, 0.5), 3.5);
        original.setLayoutX(15);
        original.setLayoutY(25);
        original.getPoints().addAll(0.0, 0.0, 40.0, 30.0, 80.0, 10.0);

        JSONObject data = serialize(original);

        assertEquals(15.0, data.getDouble("layoutX"), 1e-9);
        assertEquals(25.0, data.getDouble("layoutY"), 1e-9);
        assertEquals(3.5, data.getDouble("strokeWidth"), 1e-9);
        assertEquals(0.5, data.getDouble("opacity"), 1e-6);
        JSONArray points = data.getJSONArray("points");
        assertEquals(6, points.length());

        lineElement restored = (lineElement) deserialize("el-2", "line", data);
        assertNotNull(restored);
        assertEquals(15.0, restored.getLayoutX(), 1e-9);
        assertEquals(25.0, restored.getLayoutY(), 1e-9);
        assertEquals(3.5, restored.getStrokeWidth(), 1e-9);
        assertEquals(3, restored.pointCount());
        assertEquals(40.0, restored.getPoints().get(2), 1e-9);
        assertEquals(0.5, ((Color) restored.getStroke()).getOpacity(), 1e-6);
    }

    @Test
    void unknownElementTypeIsIgnoredInsteadOfCrashing() throws Exception {
        assertNull(deserialize("el-3", "unknown", new JSONObject()));
    }

    @Test
    void figureDefaultsAreStableWhenFieldsAreMissing() throws Exception {
        JSONObject minimal = new JSONObject().put("figureType", "rect");
        Selectable restored = deserialize("el-4", "figure", minimal);

        assertTrue(restored instanceof figureElement);
        assertEquals(100.0, ((figureElement) restored).getWidth(), 1e-9);
        assertEquals(80.0, ((figureElement) restored).getHeight(), 1e-9);
    }
}
