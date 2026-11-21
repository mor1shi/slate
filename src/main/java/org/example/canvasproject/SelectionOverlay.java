package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

public class SelectionOverlay extends Group {

    private static final double HANDLE_SIZE  = 9.0;
    private static final double PADDING      = 6.0;
    private static final String ACCENT_COLOR = "#0078FF";

    private final Selectable target;
    private final SelectionManager manager;
    private final CommandManager commandManager;

    private final Rectangle selectionRect;
    private final Rectangle handleTL, handleTR, handleBL, handleBR;

    private ElementMemento gestureBefore;
    private double dragStartSceneX, dragStartSceneY;
    private boolean isDragging = false;
    private boolean moved = false;
    private double resizeStartSceneX, resizeStartSceneY;
    private double resizeStartWidth, resizeStartHeight;
    private boolean resized = false;

    public SelectionOverlay(Selectable target, SelectionManager manager) {
        this.target = target;
        this.manager = manager;
        this.commandManager = manager.getCommandManager();

        if (target instanceof customTextField tf) tf.setActiveOverlay(this);
        if (target instanceof stickyNote sn)      sn.setActiveOverlay(this);

        selectionRect = buildSelectionRect();
        handleTL = buildHandle("NW", Cursor.NW_RESIZE);
        handleTR = buildHandle("NE", Cursor.NE_RESIZE);
        handleBL = buildHandle("SW", Cursor.SW_RESIZE);
        handleBR = buildHandle("SE", Cursor.SE_RESIZE);

        getChildren().add(selectionRect);
        if (target.isResizable()) {
            getChildren().addAll(handleTL, handleTR, handleBL, handleBR);
        }
        refresh();
    }

    private Rectangle buildSelectionRect() {
        Rectangle r = new Rectangle();
        r.setFill(Color.TRANSPARENT);
        r.setStroke(Color.web(ACCENT_COLOR));
        r.setStrokeWidth(1.5);
        r.getStrokeDashArray().addAll(7.0, 4.0);
        r.setCursor(Cursor.MOVE);
        setupDragOnRect(r);
        return r;
    }

    private Rectangle buildHandle(String anchor, Cursor cursor) {
        Rectangle h = new Rectangle(HANDLE_SIZE, HANDLE_SIZE);
        h.setFill(Color.WHITE);
        h.setStroke(Color.web(ACCENT_COLOR));
        h.setStrokeWidth(1.5);
        h.setCursor(cursor);
        h.setOnMouseEntered(e -> h.setFill(Color.web("#CCE4FF")));
        h.setOnMouseExited(e  -> h.setFill(Color.WHITE));
        setupResizeOnHandle(h, anchor);
        return h;
    }

    private void setupDragOnRect(Rectangle rect) {
        rect.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getClickCount() == 2) return;
            isDragging = true;
            moved = false;
            gestureBefore = target.save();
            dragStartSceneX = e.getSceneX();
            dragStartSceneY = e.getSceneY();
            e.consume();
        });

        rect.setOnMouseDragged(e -> {
            if (!isDragging) return;
            double dx, dy;
            Parent p = getParent();
            if (p != null) {
                Point2D curr  = p.sceneToLocal(e.getSceneX(), e.getSceneY());
                Point2D start = p.sceneToLocal(dragStartSceneX, dragStartSceneY);
                dx = curr.getX() - start.getX();
                dy = curr.getY() - start.getY();
            } else {
                dx = e.getSceneX() - dragStartSceneX;
                dy = e.getSceneY() - dragStartSceneY;
            }
            dragStartSceneX = e.getSceneX();
            dragStartSceneY = e.getSceneY();
            target.move(dx, dy);
            if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) moved = true;
            refresh();
            e.consume();
        });

        rect.setOnMouseReleased(e -> {
            if (!isDragging) return;
            isDragging = false;
            if (moved) {
                commandManager.execute(new ChangeStateCommand(target, gestureBefore, target.save()));
                manager.notifyElementChanged(target);
            }
            e.consume();
        });

        rect.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                if (target.supportsEditMode()) {
                    target.enterEditMode();
                    refresh();
                }
                e.consume();
            }
        });
    }

    private void setupResizeOnHandle(Rectangle handle, String anchor) {
        handle.setOnMousePressed(e -> {
            Bounds b = target.getSelectBounds();
            gestureBefore = target.save();
            resized = false;
            resizeStartSceneX = e.getSceneX();
            resizeStartSceneY = e.getSceneY();
            resizeStartWidth  = b.getWidth();
            resizeStartHeight = b.getHeight();
            e.consume();
        });

        handle.setOnMouseDragged(e -> {
            double dx, dy;
            Parent p = getParent();
            if (p != null) {
                Point2D curr  = p.sceneToLocal(e.getSceneX(), e.getSceneY());
                Point2D start = p.sceneToLocal(resizeStartSceneX, resizeStartSceneY);
                dx = curr.getX() - start.getX();
                dy = curr.getY() - start.getY();
            } else {
                dx = e.getSceneX() - resizeStartSceneX;
                dy = e.getSceneY() - resizeStartSceneY;
            }

            double newW = resizeStartWidth;
            double newH = resizeStartHeight;
            switch (anchor) {
                case "SE": newW = resizeStartWidth + dx;  newH = resizeStartHeight + dy;  break;
                case "SW": newW = resizeStartWidth - dx;  newH = resizeStartHeight + dy;  break;
                case "NE": newW = resizeStartWidth + dx;  newH = resizeStartHeight - dy;  break;
                case "NW": newW = resizeStartWidth - dx;  newH = resizeStartHeight - dy;  break;
            }
            newW = Math.max(newW, 30);
            newH = Math.max(newH, 20);
            target.resize(anchor, newW, newH);
            resized = true;
            refresh();
            e.consume();
        });

        handle.setOnMouseReleased(e -> {
            if (resized) {
                commandManager.execute(new ChangeStateCommand(target, gestureBefore, target.save()));
                // FIX: уведомляем сервер об изменении размера
                manager.notifyElementChanged(target);
            }
            resized = false;
            e.consume();
        });
    }

    public void refresh() {
        Bounds b = target.getSelectBounds();
        if (b == null) return;

        double x = b.getMinX() - PADDING;
        double y = b.getMinY() - PADDING;
        double w = b.getWidth()  + PADDING * 2;
        double h = b.getHeight() + PADDING * 2;

        selectionRect.setX(x);
        selectionRect.setY(y);
        selectionRect.setWidth(w);
        selectionRect.setHeight(h);

        double hs = HANDLE_SIZE / 2;
        placeHandle(handleTL, x - hs,       y - hs);
        placeHandle(handleTR, x + w - hs,   y - hs);
        placeHandle(handleBL, x - hs,       y + h - hs);
        placeHandle(handleBR, x + w - hs,   y + h - hs);

        boolean resizable = target.isResizable();
        handleTL.setVisible(resizable);
        handleTR.setVisible(resizable);
        handleBL.setVisible(resizable);
        handleBR.setVisible(resizable);
    }

    private void placeHandle(Rectangle h, double cx, double cy) {
        h.setX(cx);
        h.setY(cy);
    }

    public Selectable getTarget() { return target; }
}
