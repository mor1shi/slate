package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;

import java.util.UUID;

public class figureElement implements Selectable {

    private final String id = UUID.randomUUID().toString();
    private final String figureType;

    // Позиция и размер фигуры
    private double x, y, width, height;

    // Цвет заливки и обводки
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
    }

    private Shape buildShape() {
        Shape s = switch (figureType) {
            case "circle"   -> buildEllipse();
            case "triangle" -> buildTriangle();
            case "star"     -> buildStar();
            case "arrow"    -> buildArrow();
            default         -> buildRect();
        };

        applyStyle(s);
        return s;
    }

    private Rectangle buildRect() {
        Rectangle r = new Rectangle(x, y, width, height);
        r.setArcWidth(6);
        r.setArcHeight(6);
        return r;
    }

    private Ellipse buildEllipse() {
        Ellipse e = new Ellipse(x + width / 2, y + height / 2, width / 2, height / 2);
        return e;
    }

    private Polygon buildTriangle() {
        return new Polygon(
            x + width / 2, y,
            x + width,     y + height,
            x,             y + height
        );
    }

    private Polygon buildStar() {
        double cx = x + width / 2;
        double cy = y + height / 2;
        double outerR = Math.min(width, height) / 2;
        double innerR = outerR * 0.42;
        int n = 5;
        double[] pts = new double[n * 4];
        for (int i = 0; i < n; i++) {
            double outerAngle = Math.toRadians(-90 + i * 360.0 / n);
            double innerAngle = outerAngle + Math.toRadians(180.0 / n);
            pts[i*4]   = cx + outerR * Math.cos(outerAngle);
            pts[i*4+1] = cy + outerR * Math.sin(outerAngle);
            pts[i*4+2] = cx + innerR * Math.cos(innerAngle);
            pts[i*4+3] = cy + innerR * Math.sin(innerAngle);
        }
        return new Polygon(pts);
    }

    private Polygon buildArrow() {
        double hw = height * 0.3;
        double tip = x + width;
        double neck = x + width * 0.62;

        return new Polygon(
            x,     y + height/2 - hw,
            neck,  y + height/2 - hw,
            neck,  y,
            tip,   y + height/2,
            neck,  y + height,
            neck,  y + height/2 + hw,
            x,     y + height/2 + hw
        );
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

        var oldNode = node;
        node = buildShape();

        if (oldNode.getParent() instanceof javafx.scene.Group g) {
            int idx = g.getChildren().indexOf(oldNode);
            if (idx >= 0) g.getChildren().set(idx, node);
        }
    }

    @Override public String getElementId() { return id; }
    @Override public String getType()      { return "figure:" + figureType; }
    @Override public Node   getNode()      { return node; }

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

        width  = Math.max(newWidth, 20);
        height = Math.max(newHeight, 20);

        if (anchor.equals("NW") || anchor.equals("SW")) x = oldX + (oldW - width);
        if (anchor.equals("NW") || anchor.equals("NE")) y = oldY + (oldH - height);

        updateNode();
    }

    @Override public boolean isResizable()     { return true; }
    @Override public boolean supportsEditMode(){ return false; }

    // Memento — для undo/redo
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

    // Публичные геттеры для контроллера
    public String  getFigureType()  { return figureType; }
    public Color   getFillColor()   { return fillColor; }
    public void    setFillColor(Color c) { fillColor = c; strokeColor = c.darker(); updateNode(); }
}
