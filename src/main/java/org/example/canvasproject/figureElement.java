package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;

import java.util.UUID;

public class figureElement implements Selectable {

    private String id = UUID.randomUUID().toString();
    private final String figureType;

    private double x, y, width, height;
    private Color fillColor;
    private Color strokeColor;
    private double strokeWidth = 2.0;

    private Shape node;

    public figureElement(String figureType, double x, double y,
                         double width, double height, Color fillColor) {
        this.figureType = figureType;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.fillColor = fillColor;
        this.strokeColor = fillColor.equals(Color.TRANSPARENT)
                ? Color.web("#007AFF") : fillColor.darker();

        this.node = buildShape();
        applyStyle(node);
    }

    private Shape buildShape() {
        return switch (figureType) {
            case "circle"   -> new Ellipse(x + width/2, y + height/2, width/2, height/2);
            case "triangle" -> new Polygon(
                    x + width/2, y,
                    x + width,   y + height,
                    x,           y + height
            );
            case "star"     -> buildStarPolygon();
            case "arrow"    -> buildArrowPolygon();
            default         -> new Rectangle(x, y, width, height);
        };
    }

    private Polygon buildStarPolygon() {
        double cx = x + width/2;
        double cy = y + height/2;
        double outerR = Math.min(width, height)/2;
        double innerR = outerR * 0.42;
        int n = 5;
        Double[] pts = new Double[n*4];
        for (int i = 0; i < n; i++) {
            double outerAngle = Math.toRadians(-90 + i * 360.0 / n);
            double innerAngle = outerAngle + Math.toRadians(180.0 / n);
            pts[i*4]   = cx + outerR * Math.cos(outerAngle);
            pts[i*4+1] = cy + outerR * Math.sin(outerAngle);
            pts[i*4+2] = cx + innerR * Math.cos(innerAngle);
            pts[i*4+3] = cy + innerR * Math.sin(innerAngle);
        }
        Polygon polygon = new Polygon();
        polygon.getPoints().setAll(pts);
        return polygon;
    }

    private Polygon buildArrowPolygon() {
        double hw = height * 0.3;
        double tip = x + width;
        double neck = x + width * 0.62;
        Double[] pts = new Double[]{
                x,     y + height/2 - hw,
                neck,  y + height/2 - hw,
                neck,  y,
                tip,   y + height/2,
                neck,  y + height,
                neck,  y + height/2 + hw,
                x,     y + height/2 + hw
        };
        Polygon polygon = new Polygon();
        polygon.getPoints().setAll(pts);
        return polygon;
    }

    private void applyStyle(Shape s) {
        s.setFill(fillColor);
        s.setStroke(strokeColor);
        s.setStrokeWidth(strokeWidth);
        s.setStrokeLineJoin(StrokeLineJoin.ROUND);
        s.setOnMouseEntered(e -> s.setOpacity(0.85));
        s.setOnMouseExited(e  -> s.setOpacity(1.0));
    }

    private void updateNode() {
        if (node instanceof Rectangle rect) {
            rect.setX(x);
            rect.setY(y);
            rect.setWidth(width);
            rect.setHeight(height);
        } else if (node instanceof Ellipse ell) {
            ell.setCenterX(x + width/2);
            ell.setCenterY(y + height/2);
            ell.setRadiusX(width/2);
            ell.setRadiusY(height/2);
        } else if (node instanceof Polygon poly) {
            Double[] pts;
            if ("triangle".equals(figureType)) {
                pts = new Double[]{
                        x + width/2, y,
                        x + width,   y + height,
                        x,           y + height
                };
            } else if ("star".equals(figureType)) {
                pts = starPoints();
            } else {
                pts = arrowPoints();
            }
            poly.getPoints().setAll(pts);
        }
        node.setFill(fillColor);
        node.setStroke(strokeColor);
    }

    private Double[] starPoints() {
        double cx = x + width/2;
        double cy = y + height/2;
        double outerR = Math.min(width, height)/2;
        double innerR = outerR * 0.42;
        int n = 5;
        Double[] pts = new Double[n*4];
        for (int i = 0; i < n; i++) {
            double outerAngle = Math.toRadians(-90 + i * 360.0 / n);
            double innerAngle = outerAngle + Math.toRadians(180.0 / n);
            pts[i*4]   = cx + outerR * Math.cos(outerAngle);
            pts[i*4+1] = cy + outerR * Math.sin(outerAngle);
            pts[i*4+2] = cx + innerR * Math.cos(innerAngle);
            pts[i*4+3] = cy + innerR * Math.sin(innerAngle);
        }
        return pts;
    }

    private Double[] arrowPoints() {
        double hw = height * 0.3;
        double tip = x + width;
        double neck = x + width * 0.62;
        return new Double[]{
                x,     y + height/2 - hw,
                neck,  y + height/2 - hw,
                neck,  y,
                tip,   y + height/2,
                neck,  y + height,
                neck,  y + height/2 + hw,
                x,     y + height/2 + hw
        };
    }


    @Override
    public String getElementId() { return id; }

    @Override
    public String getType() { return "figure"; }

    @Override
    public Node getNode() { return node; }

    @Override
    public Bounds getSelectBounds() {
        return new BoundingBox(x, y, width, height);
    }

    @Override
    public void move(double dx, double dy) {
        x += dx;
        y += dy;
        updateNode();
    }

    @Override
    public void resize(String anchor, double newWidth, double newHeight) {
        double oldX = x, oldY = y;
        double oldW = width, oldH = height;

        width = Math.max(newWidth, 20);
        height = Math.max(newHeight, 20);
        double newX = oldX;
        double newY = oldY;

        switch (anchor) {
            case "NW":
                newX = oldX + oldW - width;
                newY = oldY + oldH - height;
                break;
            case "NE":
                newX = oldX;
                newY = oldY + oldH - height;
                break;
            case "SW":
                newX = oldX + oldW - width;
                newY = oldY;
                break;
            case "SE":
            default:
                newX = oldX;
                newY = oldY;
                break;
        }
        x = newX;
        y = newY;
        updateNode();
    }

    @Override
    public boolean isResizable() { return true; }

    @Override
    public boolean supportsEditMode() { return false; }


    public double getX()      { return x; }
    public double getY()      { return y; }
    public double getWidth()  { return width; }
    public double getHeight() { return height; }

    public void setX(double x) {
        this.x = x;
        updateNode();
    }

    public void setY(double y) {
        this.y = y;
        updateNode();
    }

    public void setWidth(double width) {
        this.width = Math.max(width, 20);
        updateNode();
    }

    public void setHeight(double height) {
        this.height = Math.max(height, 20);
        updateNode();
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFigureType() { return figureType; }

    public Color getFillColor() { return fillColor; }

    public void setFillColor(Color c) {
        fillColor = c;
        strokeColor = c.darker();
        updateNode();
    }


    private static class State implements ElementMemento {
        final double x, y, w, h;
        final Color fill, stroke;
        State(double x, double y, double w, double h, Color fill, Color stroke) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.fill = fill; this.stroke = stroke;
        }
    }

    @Override
    public ElementMemento save() {
        return new State(x, y, width, height, fillColor, strokeColor);
    }

    @Override
    public void restore(ElementMemento m) {
        State s = (State) m;
        x = s.x; y = s.y; width = s.w; height = s.h;
        fillColor = s.fill; strokeColor = s.stroke;
        updateNode();
    }
}
