package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.canvas.GraphicsContext;

public interface drawable extends Selectable {
    void draw(GraphicsContext gc);
    boolean contains(double x, double y);
    void translate(double dx, double dy);
    Bounds getBounds();
    @Override default void move(double dx, double dy) { translate(dx, dy); }
    String getId();
    void setId(String id);
}