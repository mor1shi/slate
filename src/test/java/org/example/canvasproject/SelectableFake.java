package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;

/**
 * Элемент без JavaFX-ноды для геометрических тестов: рамка, перемещение и
 * снимок состояния работают так же, как у настоящих элементов.
 */
final class SelectableFake implements Selectable {

    private final String id;
    private final String type;
    private final Node node;
    private double x, y, width, height;
    private double opacity = 1.0;

    SelectableFake(String id, String type, double x, double y, double width, double height) {
        this(id, type, x, y, width, height, null);
    }

    SelectableFake(String id, String type, double x, double y, double width, double height, Node node) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.node = node;
    }

    @Override public String getElementId() { return id; }
    @Override public String getType() { return type; }
    @Override public Node getNode() { return node; }
    @Override public Bounds getSelectBounds() {
        return new BoundingBox(x, y, width, height);
    }

    @Override
    public void move(double dx, double dy) {
        x += dx;
        y += dy;
    }

    @Override
    public void resize(String anchor, double newWidth, double newHeight) {
        double oldW = width, oldH = height;
        width = Math.max(newWidth, 1);
        height = Math.max(newHeight, 1);
        switch (anchor) {
            case "NW" -> { x += oldW - width; y += oldH - height; }
            case "NE" -> { y += oldH - height; }
            case "SW" -> { x += oldW - width; }
            default -> { }
        }
    }

    double opacity() { return opacity; }
    void setOpacity(double opacity) { this.opacity = opacity; }

    @Override public ElementMemento save() {
        return new State(x, y, width, height, opacity);
    }

    @Override public void restore(ElementMemento memento) {
        State s = (State) memento;
        x = s.x; y = s.y; width = s.w; height = s.h; opacity = s.opacity;
    }

    double x() { return x; }
    double y() { return y; }
    double width() { return width; }
    double height() { return height; }

    private static final class State implements ElementMemento {
        final double x, y, w, h, opacity;
        State(double x, double y, double w, double h, double opacity) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.opacity = opacity;
        }
    }
}
