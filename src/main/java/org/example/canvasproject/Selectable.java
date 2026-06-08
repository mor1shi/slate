package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.Node;

/**
 * Универсальный контракт любого элемента доски - линия, текст, фото, и т.д.
 *
 * Всё что лежит на доске — это нода JavaFX (getNode()), которая
 * умеет: сказать свои границы, подвинуться, изменить размер, и сделать снимок
 * своего состояния (save/restore) для undo/redo и будущей сериализации в MongoDB.
 *
 */
public interface Selectable {

    String getElementId();

    String getType();

    Node getNode();

    Bounds getSelectBounds();

    void move(double dx, double dy);

    void resize(String anchor, double newWidth, double newHeight);

    ElementMemento save();

    void restore(ElementMemento memento);

    default boolean contains(double x, double y) {
        Bounds b = getSelectBounds();
        return b != null && b.contains(x, y);
    }

    default boolean isResizable() { return true; }
    default boolean supportsEditMode() { return false; }
    default void enterEditMode() {}
    default void exitEditMode() {}
}
