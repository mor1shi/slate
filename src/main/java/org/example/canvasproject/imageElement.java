package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.UUID;

/**
 * Фотография как элемент доски
 *
 * Реализуем тот же интерфейс Selectable — и фото сразу умеет выделяться,
 * двигаться, ресайзиться и удаляться через общий SelectionManager
 */
public class imageElement extends ImageView implements Selectable {

    private final String id = UUID.randomUUID().toString();

    public imageElement(Image image, double x, double y, double width, double height) {
        super(image);
        setLayoutX(x);
        setLayoutY(y);
        setFitWidth(width);
        setFitHeight(height);
        setPreserveRatio(false);
        setCursor(Cursor.HAND);
    }

    @Override public String getElementId() { return id; }
    @Override public String getType() { return "image"; }
    @Override public Node getNode() { return this; }

    @Override
    public Bounds getSelectBounds() {
        return new BoundingBox(getLayoutX(), getLayoutY(), getFitWidth(), getFitHeight());
    }

    @Override
    public void move(double dx, double dy) {
        setLayoutX(getLayoutX() + dx);
        setLayoutY(getLayoutY() + dy);
    }

    @Override
    public void resize(String anchor, double newWidth, double newHeight) {
        double oldX = getLayoutX(), oldY = getLayoutY();
        double oldW = getFitWidth(), oldH = getFitHeight();
        double w = Math.max(newWidth, 30);
        double h = Math.max(newHeight, 30);
        setFitWidth(w);
        setFitHeight(h);
        if (anchor.equals("NW") || anchor.equals("SW")) setLayoutX(oldX + (oldW - w));
        if (anchor.equals("NW") || anchor.equals("NE")) setLayoutY(oldY + (oldH - h));
    }

    @Override public boolean isResizable() { return true; }

    private static class State implements ElementMemento {
        final double x, y, w, h;
        State(double x, double y, double w, double h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }

    @Override
    public ElementMemento save() {
        return new State(getLayoutX(), getLayoutY(), getFitWidth(), getFitHeight());
    }

    @Override
    public void restore(ElementMemento memento) {
        State s = (State) memento;
        setLayoutX(s.x);
        setLayoutY(s.y);
        setFitWidth(s.w);
        setFitHeight(s.h);
    }
}
