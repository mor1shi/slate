package org.example.canvasproject;

import javafx.scene.Group;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Групповые операции выделения: одно действие — одна команда Undo.
 */
class GroupSelectionTest {

    private SelectionManager manager(BoardModel model, CommandManager history) {
        return new SelectionManager(new Group(), model, history);
    }

    private BoardModel modelWithFive() {
        BoardModel model = new BoardModel(null);
        for (int i = 0; i < 5; i++) {
            model.add(new SelectableFake("e" + i, "figure", i * 100, 0, 80, 60));
        }
        return model;
    }

    @Test
    void groupDeleteIsOneCommandAndUndoRestoresExactOrder() {
        BoardModel model = modelWithFive();
        CommandManager history = new CommandManager();
        SelectionManager selection = manager(model, history);
        selection.setSelection(List.of(model.elements().get(1), model.elements().get(3)));

        selection.deleteSelected();

        assertEquals(List.of("e0", "e2", "e4"), ids(model));
        assertTrue(selection.getSelection().isEmpty());

        assertTrue(history.undo());
        assertEquals(List.of("e0", "e1", "e2", "e3", "e4"), ids(model),
                "Undo возвращает элементы на прежние места в z-порядке");

        assertTrue(history.redo());
        assertEquals(List.of("e0", "e2", "e4"), ids(model));
    }

    @Test
    void groupMoveIsOneCommandAndUndoRestoresExactPositions() {
        BoardModel model = modelWithFive();
        CommandManager history = new CommandManager();
        SelectionManager selection = manager(model, history);
        selection.setSelection(List.of(model.elements().get(0), model.elements().get(2)));

        selection.beginGesture();
        selection.moveGesture(30, 40);
        GroupChangeCommand command = selection.endGesture();

        assertNotNull(command);
        assertEquals(30, ((SelectableFake) model.elements().get(0)).x(), 1e-9);
        assertEquals(40, ((SelectableFake) model.elements().get(0)).y(), 1e-9);
        assertEquals(230, ((SelectableFake) model.elements().get(2)).x(), 1e-9);

        assertTrue(history.undo());
        assertEquals(0, ((SelectableFake) model.elements().get(0)).x(), 1e-9);
        assertEquals(0, ((SelectableFake) model.elements().get(0)).y(), 1e-9);
        assertEquals(200, ((SelectableFake) model.elements().get(2)).x(), 1e-9);

        assertTrue(history.redo());
        assertEquals(30, ((SelectableFake) model.elements().get(0)).x(), 1e-9);
    }

    @Test
    void groupResizeScalesAroundGroupFrameAndUndoesExactly() {
        BoardModel model = new BoardModel(null);
        SelectableFake a = new SelectableFake("a", "figure", 0, 0, 100, 100);
        SelectableFake b = new SelectableFake("b", "figure", 200, 0, 100, 100);
        model.add(a);
        model.add(b);
        CommandManager history = new CommandManager();
        SelectionManager selection = manager(model, history);
        selection.setSelection(List.of(a, b));

        selection.beginGesture();
        selection.resizeGesture("SE", 30, 10, false);
        selection.endGesture();

        assertEquals(110, a.width(), 1e-9);
        assertEquals(110, a.height(), 1e-9);
        assertEquals(220, b.x(), 1e-9);
        assertEquals(110, b.width(), 1e-9);

        assertTrue(history.undo());
        assertEquals(100, a.width(), 1e-9);
        assertEquals(200, b.x(), 1e-9);
        assertEquals(100, b.width(), 1e-9);
    }

    @Test
    void remoteRemoveDropsElementFromSelectionWithoutBreakingOthers() {
        BoardModel model = modelWithFive();
        SelectionManager selection = manager(model, new CommandManager());
        Selectable a = model.elements().get(0);
        Selectable b = model.elements().get(1);
        Selectable c = model.elements().get(2);
        selection.setSelection(List.of(a, b, c));

        selection.handleRemoteRemoved(b);
        model.remove(b);

        assertEquals(List.of(a, c), selection.getSelection());
        assertEquals(2, selection.selectionCount());
        assertSame(a, selection.getSelected(), "после удаления выбранным остаётся первый живой элемент");
        assertFalse(selection.getSelection().contains(b));

        // Групповое удаление пропускает уже исчезнувший элемент.
        selection.deleteSelected();
        assertEquals(List.of("e3", "e4"), ids(model));
        assertNull(selection.getSelected());
    }

    @Test
    void remoteChangeRefreshesSelectionWithoutThrowing() {
        BoardModel model = modelWithFive();
        SelectionManager selection = manager(model, new CommandManager());
        Selectable a = model.elements().get(0);
        selection.setSelection(List.of(a));

        selection.handleRemoteChanged(a);

        assertTrue(selection.getSelection().isEmpty(),
                "изменённый другим участником элемент тихо выпадает из выделения");
    }

    @Test
    void groupOpacityAndColorAreOneUndoableCommand() {
        BoardModel model = new BoardModel(null);
        javafx.scene.shape.Rectangle nodeA = new javafx.scene.shape.Rectangle();
        javafx.scene.shape.Rectangle nodeB = new javafx.scene.shape.Rectangle();
        SelectableFake a = new SelectableFake("a", "figure", 0, 0, 50, 50, nodeA);
        SelectableFake b = new SelectableFake("b", "figure", 100, 0, 50, 50, nodeB);
        model.add(a);
        model.add(b);
        CommandManager history = new CommandManager();
        SelectionManager selection = manager(model, history);
        selection.setSelection(List.of(a, b));

        GroupChangeCommand command = selection.applyOpacity(0.4);

        assertNotNull(command);
        assertEquals(0.4, nodeA.getOpacity(), 1e-9);
        assertEquals(0.4, nodeB.getOpacity(), 1e-9);

        assertTrue(history.undo());
        assertEquals(1.0, nodeA.getOpacity(), 1e-9,
                "Undo возвращает прозрачность узлов, а не только снимок элемента");
        assertEquals(1.0, nodeB.getOpacity(), 1e-9);

        assertTrue(history.redo());
        assertEquals(0.4, nodeA.getOpacity(), 1e-9);
    }

    private static List<String> ids(BoardModel model) {
        return model.elements().stream().map(Selectable::getElementId).toList();
    }
}
