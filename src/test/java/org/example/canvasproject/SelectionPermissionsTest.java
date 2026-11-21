package org.example.canvasproject;

import javafx.scene.Group;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Регрессия H-18: наблюдатель не получает ручки ресайза и не может удалять.
 * Состояние «только просмотр» проверяется без запуска сцены.
 */
class SelectionPermissionsTest {

    private SelectionManager manager(boolean editable) {
        SelectionManager manager = new SelectionManager(new Group(), new BoardModel(new Group()),
                new CommandManager());
        manager.setEditable(editable);
        return manager;
    }

    @Test
    void viewerGetsOnlyOutlineWithoutResizeHandles() {
        figureElement figure = new figureElement("rect", 0, 0, 100, 80,
                javafx.scene.paint.Color.BLUE);
        SelectionOverlay overlay = new SelectionOverlay(figure, manager(false), false);

        assertEquals(1, overlay.getChildren().size(),
                "в режиме просмотра остаётся только рамка, без четырёх ручек");
    }

    @Test
    void editorKeepsResizeHandles() {
        figureElement figure = new figureElement("rect", 0, 0, 100, 80,
                javafx.scene.paint.Color.BLUE);
        SelectionOverlay overlay = new SelectionOverlay(figure, manager(true), true);

        assertEquals(5, overlay.getChildren().size());
    }

    @Test
    void editableFlagDefaultsToEditorAndCanBeRevoked() {
        SelectionManager manager = manager(true);
        assertTrue(manager.isEditable());
        manager.setEditable(false);
        assertFalse(manager.isEditable());
    }
}
