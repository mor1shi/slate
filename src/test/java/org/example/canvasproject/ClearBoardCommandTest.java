package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClearBoardCommandTest {

    /** Элемент без JavaFX-ноды: команде важны только модель и порядок. */
    private static final class FakeElement implements Selectable {
        private final String id;

        FakeElement(String id) { this.id = id; }

        @Override public String getElementId() { return id; }
        @Override public String getType() { return "fake"; }
        @Override public Node getNode() { return null; }
        @Override public Bounds getSelectBounds() { return null; }
        @Override public void move(double dx, double dy) {}
        @Override public void resize(String anchor, double w, double h) {}
        @Override public ElementMemento save() { return null; }
        @Override public void restore(ElementMemento memento) {}
    }

    @Test
    void clearIsUndoableAndKeepsOrder() {
        BoardModel model = new BoardModel(null);
        Selectable a = new FakeElement("a");
        Selectable b = new FakeElement("b");
        model.add(a);
        model.add(b);

        List<String> deleted = new ArrayList<>();
        List<String> restored = new ArrayList<>();
        ClearBoardCommand command = new ClearBoardCommand(model,
                el -> deleted.add(el.getElementId()),
                el -> restored.add(el.getElementId()));

        CommandManager history = new CommandManager();
        history.execute(command);

        assertTrue(model.elements().isEmpty());
        assertEquals(List.of("a", "b"), deleted);

        assertTrue(history.undo());
        assertEquals(2, model.elements().size());
        assertEquals("a", model.elements().get(0).getElementId());
        assertEquals("b", model.elements().get(1).getElementId());
        assertEquals(List.of("a", "b"), restored);

        assertTrue(history.redo());
        assertTrue(model.elements().isEmpty());
        assertEquals(4, deleted.size(), "redo снова удаляет оба элемента");
    }
}
