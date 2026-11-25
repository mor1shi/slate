package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * Рамка группы: общий контур, тонкие контуры элементов и ручки масштаба.
 * Наблюдателю ручки не показываются.
 */
public class GroupSelectionOverlay extends Group {

    private static final double HANDLE_SIZE = 9.0;
    private static final double PADDING = 6.0;
    private static final String ACCENT = "#0078FF";

    private final List<Selectable> targets;
    private final SelectionManager manager;
    private final boolean editable;

    private final Rectangle frame;
    private final Group contours = new Group();
    private final Rectangle handleTL, handleTR, handleBL, handleBR;

    private double dragStartSceneX, dragStartSceneY;

    public GroupSelectionOverlay(List<Selectable> targets, SelectionManager manager, boolean editable) {
        this.targets = new ArrayList<>(targets);
        this.manager = manager;
        this.editable = editable;

        frame = new Rectangle();
        frame.setFill(Color.TRANSPARENT);
        frame.setStroke(Color.web(ACCENT));
        frame.setStrokeWidth(1.5);
        contours.setMouseTransparent(true);

        handleTL = buildHandle(Cursor.NW_RESIZE, "NW");
        handleTR = buildHandle(Cursor.NE_RESIZE, "NE");
        handleBL = buildHandle(Cursor.SW_RESIZE, "SW");
        handleBR = buildHandle(Cursor.SE_RESIZE, "SE");

        getChildren().addAll(contours, frame);
        if (editable) {
            getChildren().addAll(handleTL, handleTR, handleBL, handleBR);
            setupMove();
        }
        refresh();
    }

    private Rectangle buildHandle(Cursor cursor, String anchor) {
        Rectangle h = new Rectangle(HANDLE_SIZE, HANDLE_SIZE);
        h.setFill(Color.WHITE);
        h.setStroke(Color.web(ACCENT));
        h.setStrokeWidth(1.5);
        h.setCursor(cursor);
        h.setOnMouseEntered(e -> h.setFill(Color.web("#CCE4FF")));
        h.setOnMouseExited(e -> h.setFill(Color.WHITE));
        setupResize(h, anchor);
        return h;
    }

    private void setupMove() {
        frame.setCursor(Cursor.MOVE);
        frame.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (!manager.beginGesture()) return;
            dragStartSceneX = e.getSceneX();
            dragStartSceneY = e.getSceneY();
            e.consume();
        });
        frame.setOnMouseDragged(e -> {
            Point2D delta = canvasDelta(e.getSceneX(), e.getSceneY());
            double dx = delta.getX();
            double dy = delta.getY();
            Bounds start = manager.getGestureStartBounds();
            if (manager.isGridSnap() && start != null) {
                double step = manager.getGridSize();
                dx = Math.round((start.getMinX() + dx) / step) * step - start.getMinX();
                dy = Math.round((start.getMinY() + dy) / step) * step - start.getMinY();
            }
            manager.moveGesture(dx, dy);
            e.consume();
        });
        frame.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            manager.endGesture();
            e.consume();
        });
    }

    private void setupResize(Rectangle handle, String anchor) {
        handle.setOnMousePressed(e -> {
            if (!manager.beginGesture()) return;
            dragStartSceneX = e.getSceneX();
            dragStartSceneY = e.getSceneY();
            e.consume();
        });
        handle.setOnMouseDragged(e -> {
            Point2D delta = canvasDelta(e.getSceneX(), e.getSceneY());
            manager.resizeGesture(anchor, delta.getX(), delta.getY(), e.isShiftDown());
            e.consume();
        });
        handle.setOnMouseReleased(e -> {
            manager.endGesture();
            e.consume();
        });
    }

    private Point2D canvasDelta(double sceneX, double sceneY) {
        Parent parent = getParent();
        if (parent == null) return new Point2D(sceneX - dragStartSceneX, sceneY - dragStartSceneY);
        Point2D current = parent.sceneToLocal(sceneX, sceneY);
        Point2D start = parent.sceneToLocal(dragStartSceneX, dragStartSceneY);
        return new Point2D(current.getX() - start.getX(), current.getY() - start.getY());
    }

    public void refresh() {
        contours.getChildren().clear();
        Bounds union = SelectionGeometry.unionBounds(targets);
        if (union == null) return;

        for (Selectable el : targets) {
            Bounds b = el.getSelectBounds();
            if (b == null) continue;
            Rectangle outline = new Rectangle(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
            outline.setFill(Color.web(ACCENT, 0.06));
            outline.setStroke(Color.web(ACCENT, 0.75));
            outline.setStrokeWidth(1);
            outline.setMouseTransparent(true);
            contours.getChildren().add(outline);
        }

        double x = union.getMinX() - PADDING;
        double y = union.getMinY() - PADDING;
        double w = union.getWidth() + PADDING * 2;
        double h = union.getHeight() + PADDING * 2;
        frame.setX(x);
        frame.setY(y);
        frame.setWidth(w);
        frame.setHeight(h);

        double hs = HANDLE_SIZE / 2;
        place(handleTL, x - hs, y - hs);
        place(handleTR, x + w - hs, y - hs);
        place(handleBL, x - hs, y + h - hs);
        place(handleBR, x + w - hs, y + h - hs);
    }

    private void place(Rectangle h, double x, double y) {
        h.setX(x);
        h.setY(y);
    }

    public List<Selectable> getTargets() { return List.copyOf(targets); }
}
