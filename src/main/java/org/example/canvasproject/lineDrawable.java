package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class lineDrawable implements drawable {

    private List<Point2D> points;
    private Color color;
    private double lineWidth;
    private String id;

    public lineDrawable(Color color, double lineWidth) {
        this.points    = new ArrayList<>();
        this.color     = color;
        this.lineWidth = lineWidth;
        this.id        = UUID.randomUUID().toString();
    }

    public void addPoint(double x, double y) {
        points.add(new Point2D(x, y));
    }

    @Override
    public void draw(GraphicsContext gc) {
        if (points.size() < 2) return;
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.beginPath();
        gc.moveTo(points.get(0).getX(), points.get(0).getY());
        for (int i = 1; i < points.size(); i++) {
            Point2D p = points.get(i);
            gc.lineTo(p.getX(), p.getY());
        }
        gc.stroke();
    }

    @Override
    public boolean contains(double x, double y) {
        if (points.size() < 2) return false;
        double tolerance = 8.0;
        for (int i = 0; i < points.size() - 1; i++) {
            if (distanceToSegment(x, y, points.get(i), points.get(i + 1)) < tolerance)
                return true;
        }
        return false;
    }

    private double distanceToSegment(double x, double y, Point2D p1, Point2D p2) {
        double ax = p2.getX() - p1.getX(), ay = p2.getY() - p1.getY();
        double bx = x - p1.getX(),         by = y - p1.getY();
        double len2 = ax * ax + ay * ay;
        if (len2 == 0) return Math.hypot(bx, by);
        double t = Math.max(0, Math.min(1, (ax*bx + ay*by) / len2));
        return Math.hypot(x - (p1.getX() + t*ax), y - (p1.getY() + t*ay));
    }

    @Override
    public Bounds getBounds() {
        if (points.isEmpty()) return new BoundingBox(0, 0, 1, 1);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point2D p : points) {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY());
        }
        double pad = 15;
        return new BoundingBox(minX - pad, minY - pad,
                maxX - minX + pad*2, maxY - minY + pad*2);
    }

    @Override public String  getElementId()  { return id; }
    @Override public String  getType()        { return "line"; }
    @Override public Node    getNode()        { return null; } // рисуется на Canvas, не нода
    @Override public Bounds  getSelectBounds(){ return getBounds(); }

    @Override
    public void translate(double dx, double dy) {
        for (int i = 0; i < points.size(); i++) {
            Point2D p = points.get(i);
            points.set(i, new Point2D(p.getX() + dx, p.getY() + dy));
        }
    }

    @Override public void move(double dx, double dy) { translate(dx, dy); }

    @Override
    public void resize(String anchor, double newWidth, double newHeight) {
        Bounds b = getBounds();
        if (b.getWidth() <= 0 || b.getHeight() <= 0) return;
        double scaleX = newWidth  / b.getWidth();
        double scaleY = newHeight / b.getHeight();
        double cx, cy;
        switch (anchor) {
            case "NW": cx = b.getMaxX(); cy = b.getMaxY(); break;
            case "NE": cx = b.getMinX(); cy = b.getMaxY(); break;
            case "SW": cx = b.getMaxX(); cy = b.getMinY(); break;
            default:   cx = b.getMinX(); cy = b.getMinY(); break; // SE
        }
        for (int i = 0; i < points.size(); i++) {
            Point2D p = points.get(i);
            points.set(i, new Point2D(
                    cx + (p.getX() - cx) * scaleX,
                    cy + (p.getY() - cy) * scaleY
            ));
        }
    }

    private static class State implements ElementMemento {
        final List<Point2D> points; // глубокая копия
        final Color color;
        final double lineWidth;

        State(List<Point2D> points, Color color, double lineWidth) {
            this.points    = new ArrayList<>(points); // копируем, а не ссылку
            this.color     = color;
            this.lineWidth = lineWidth;
        }
    }

    @Override
    public ElementMemento save() {
        return new State(points, color, lineWidth);
    }

    @Override
    public void restore(ElementMemento memento) {
        State s = (State) memento;

        points.clear();
        points.addAll(s.points);
        color     = s.color;
        lineWidth = s.lineWidth;
    }

    @Override public boolean isResizable()     { return true; }
    @Override public boolean supportsEditMode(){ return false; }
    @Override public void    enterEditMode()   {}

    @Override public String getId()           { return id; }
    @Override public void   setId(String id)  { this.id = id; }

    public List<Point2D> getPoints()  { return points; }
    public Color         getColor()   { return color; }
    public double        getLineWidth(){ return lineWidth; }
}