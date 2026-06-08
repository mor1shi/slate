package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

public class SelectionOverlay extends Group {

    private static final double HANDLE_SIZE  = 9.0;
    private static final double DELETE_SIZE  = 18.0;
    private static final double PADDING      = 6.0;
    private static final String ACCENT_COLOR = "#0078FF";
    private static final String DELETE_COLOR = "#FF3B30";

    private final Selectable target;
    private final SelectionManager manager;
    private final CommandManager commandManager;

    private final Rectangle selectionRect;
    private final Rectangle handleTL, handleTR, handleBL, handleBR;
    private final Group deleteButton;

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
        if (target instanceof stickyNote sn)      sn.setActiveOverlay(this); // ИЗМЕНЕНИЕ

        selectionRect = buildSelectionRect();
        handleTL = buildHandle("NW", Cursor.NW_RESIZE);
        handleTR = buildHandle("NE", Cursor.NE_RESIZE);
        handleBL = buildHandle("SW", Cursor.SW_RESIZE);
        handleBR = buildHandle("SE", Cursor.SE_RESIZE);
        deleteButton = buildDeleteButton();

        getChildren().addAll(selectionRect, deleteButton);
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

    private Group buildDeleteButton() {
        Rectangle bg = new Rectangle(DELETE_SIZE, DELETE_SIZE);
        bg.setArcWidth(DELETE_SIZE);
        bg.setArcHeight(DELETE_SIZE);
        bg.setFill(Color.web(DELETE_COLOR));
        bg.setStroke(Color.WHITE);
        bg.setStrokeWidth(1.5);

        double pad = 5.0;
        Line l1 = new Line(pad, pad, DELETE_SIZE - pad, DELETE_SIZE - pad);
        Line l2 = new Line(DELETE_SIZE - pad, pad, pad, DELETE_SIZE - pad);
        l1.setStroke(Color.WHITE); l1.setStrokeWidth(2);
        l2.setStroke(Color.WHITE); l2.setStrokeWidth(2);

        Group btn = new Group(bg, l1, l2);
        btn.setCursor(Cursor.HAND);
        btn.setOnMouseEntered(e -> bg.setFill(Color.web("#CC2A20")));
        btn.setOnMouseExited(e  -> bg.setFill(Color.web(DELETE_COLOR)));

        btn.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                manager.deleteSelected();
            }
            e.consume();
        });
        return btn;
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
            double dx = e.getSceneX() - dragStartSceneX;
            double dy = e.getSceneY() - dragStartSceneY;
            dragStartSceneX = e.getSceneX();
            dragStartSceneY = e.getSceneY();
            target.move(dx, dy);
            if (Math.abs(dx) > 0 || Math.abs(dy) > 0) moved = true;
            refresh();
            e.consume();
        });

        rect.setOnMouseReleased(e -> {
            if (!isDragging) return;
            isDragging = false;
            if (moved) {
                commandManager.execute(new ChangeStateCommand(target, gestureBefore, target.save()));
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
            double dx = e.getSceneX() - resizeStartSceneX;
            double dy = e.getSceneY() - resizeStartSceneY;
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

        deleteButton.setLayoutX(x + w - DELETE_SIZE / 2);
        deleteButton.setLayoutY(y - DELETE_SIZE / 2);

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