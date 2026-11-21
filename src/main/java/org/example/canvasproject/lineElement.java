package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.UUID;

/**
 * Линия как нода Polyline, а не рисунок на Canvas
 *
 * За счёт этого линия обычный элемент: её можно выделить кликом, двигать,
 * ресайзить и удалять тем же кодом, что текст и фото
 *
 */
public class lineElement extends Polyline implements Selectable {

    private final String id = UUID.randomUUID().toString();

    public lineElement(Color color, double width) {
        setStroke(color);
        setStrokeWidth(width);
        setStrokeLineCap(StrokeLineCap.ROUND);
        setStrokeLineJoin(StrokeLineJoin.ROUND);
    }

    public void addPoint(double x, double y) {
        getPoints().addAll(x - getLayoutX(), y - getLayoutY());
    }

    public int pointCount() { return getPoints().size() / 2; }

    /** Одиночный клик пером: две почти совпавшие точки дают круглый оттиск. */
    public void ensureDot() {
        if (pointCount() != 1) return;
        double x = getPoints().get(0);
        double y = getPoints().get(1);
        getPoints().addAll(x + 0.01, y + 0.01);
    }

    @Override public String getElementId() { return id; }
    @Override public String getType() { return "line"; }
    @Override public Node getNode() { return this; }

    @Override
    public Bounds getSelectBounds() {
        return getBoundsInParent();
    }

    @Override
    public void move(double dx, double dy) {
        setLayoutX(getLayoutX() + dx);
        setLayoutY(getLayoutY() + dy);
    }

    @Override
    public void resize(String anchor, double newWidth, double newHeight) {
        Bounds b = getBoundsInParent();
        double oldW = b.getWidth(), oldH = b.getHeight();
        if (oldW <= 0 || oldH <= 0) return;
        double scaleX = newWidth / oldW;
        double scaleY = newHeight / oldH;

        double cx, cy;
        switch (anchor) {
            case "NW": cx = b.getMaxX(); cy = b.getMaxY(); break;
            case "NE": cx = b.getMinX(); cy = b.getMaxY(); break;
            case "SW": cx = b.getMaxX(); cy = b.getMinY(); break;
            default:   cx = b.getMinX(); cy = b.getMinY(); break; // SE
        }

        var pts = getPoints();
        for (int i = 0; i < pts.size(); i += 2) {
            double px = pts.get(i)     + getLayoutX();
            double py = pts.get(i + 1) + getLayoutY();
            double nx = cx + (px - cx) * scaleX;
            double ny = cy + (py - cy) * scaleY;
            pts.set(i,     nx - getLayoutX());
            pts.set(i + 1, ny - getLayoutY());
        }
    }

    @Override public boolean isResizable() { return true; }

    // Memento

    private static class State implements ElementMemento {
        final double layoutX, layoutY, width;
        final Color color;
        final Double[] points;
        State(double lx, double ly, double w, Color c, Double[] pts) {
            this.layoutX = lx; this.layoutY = ly; this.width = w; this.color = c; this.points = pts;
        }
    }

    @Override
    public ElementMemento save() {
        Double[] pts = getPoints().toArray(new Double[0]);
        return new State(getLayoutX(), getLayoutY(), getStrokeWidth(), (Color) getStroke(), pts);
    }

    @Override
    public void restore(ElementMemento memento) {
        State s = (State) memento;
        setLayoutX(s.layoutX);
        setLayoutY(s.layoutY);
        setStrokeWidth(s.width);
        setStroke(s.color);
        getPoints().setAll(s.points);
    }
}
