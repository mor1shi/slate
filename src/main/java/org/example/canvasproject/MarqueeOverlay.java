package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.Collection;
import java.util.List;

/**
 * Слой рамки-«резинки». Он не перерисовывает сцену: это отдельные узлы поверх
 * холста. Во время протягивания здесь же рисуются тонкие подсветки элементов.
 */
public class MarqueeOverlay extends Group {

    public static final Color ACCENT = Color.web("#0078FF");
    public static final double FILL_OPACITY = 0.10;

    private final Rectangle frame = new Rectangle();
    private final Group highlights = new Group();

    public MarqueeOverlay() {
        setMouseTransparent(true);
        frame.setFill(Color.web("#0078FF", FILL_OPACITY));
        frame.setStroke(ACCENT);
        frame.setStrokeWidth(1);
        highlights.setMouseTransparent(true);
        getChildren().addAll(highlights, frame);
    }

    public void setFrame(Bounds bounds, boolean containMode) {
        if (bounds == null) return;
        frame.setX(bounds.getMinX());
        frame.setY(bounds.getMinY());
        frame.setWidth(bounds.getWidth());
        frame.setHeight(bounds.getHeight());
        frame.getStrokeDashArray().clear();
        if (!containMode) {
            frame.getStrokeDashArray().addAll(6.0, 4.0);
        }
    }

    public void setHighlights(Collection<? extends Selectable> elements) {
        highlights.getChildren().clear();
        if (elements == null) return;
        for (Selectable el : elements) {
            Bounds b = el.getSelectBounds();
            if (b == null) continue;
            Rectangle outline = new Rectangle(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
            outline.setFill(Color.web("#0078FF", 0.08));
            outline.setStroke(Color.web("#0078FF", 0.9));
            outline.setStrokeWidth(1);
            outline.getStrokeDashArray().addAll(4.0, 3.0);
            outline.setMouseTransparent(true);
            highlights.getChildren().add(outline);
        }
    }

    public Rectangle getFrame() { return frame; }
    public List<?> getHighlightNodes() { return List.copyOf(highlights.getChildren()); }
}
