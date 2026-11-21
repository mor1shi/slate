package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.Node;

/**
 * Универсальный контракт любого элемента доски - линия, текст, фото и тд
 *
 * Всё что лежит на доске — это нода JavaFX, которая
 * умеет: сказать свои границы, подвинуться, изменить размер, и сделать снимок
 * своего состояния (save/restore) для undo/redo
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
