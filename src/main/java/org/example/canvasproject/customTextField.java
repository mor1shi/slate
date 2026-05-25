package org.example.canvasproject;

import javafx.scene.control.TextField;
import javafx.scene.Cursor;
import javafx.scene.shape.Rectangle;
import javafx.scene.Group;

public class customTextField extends TextField {
    private static final double RESIZE_HANDLE_SIZE = 8.0;
    private static final long DOUBLE_CLICK_TIME = 500;
    private long lastClickTime = 0;

    private boolean isSelected = false;
    private boolean isDragging = false;
    private double dragStartX, dragStartY;
    private double startLayoutX, startLayoutY;

    private Rectangle topLeft, topRight, bottomLeft, bottomRight;
    private Group parentGroup;

    private double originalX;
    private double originalY;

    public customTextField(double x, double y) {
        super();
        this.originalX = x;
        this.originalY = y;
        this.setLayoutX(x);
        this.setLayoutY(y);
        this.setPrefWidth(100);
        this.setPrefHeight(40);
        this.setPromptText("");
        this.setStyle("-fx-background-color: rgba(230,230,230,0.8); -fx-border-color: transparent; -fx-font-size: 14;");

        setupBehavior();
        setupResizeHandles();

        layoutXProperty().addListener((obs, old, val) -> updateHandlesPosition());
        layoutYProperty().addListener((obs, old, val) -> updateHandlesPosition());
        widthProperty().addListener((obs, old, val) -> updateHandlesPosition());
        heightProperty().addListener((obs, old, val) -> updateHandlesPosition());
    }

    private void setupBehavior() {
        setOnMouseEntered(e -> { if (!isSelected) setCursor(Cursor.HAND); });
        setOnMouseExited(e -> { if (!isSelected) setCursor(Cursor.DEFAULT); });

        setOnMouseClicked(e -> {
            long now = System.currentTimeMillis();
            boolean doubleClick = (now - lastClickTime) < DOUBLE_CLICK_TIME;
            lastClickTime = now;
            if (doubleClick) enterEditMode();
            else selectTextField();
        });

        setOnMousePressed(e -> {
            if (isSelected && !isDragging) {
                dragStartX = e.getSceneX();
                dragStartY = e.getSceneY();
                startLayoutX = getLayoutX();
                startLayoutY = getLayoutY();
                isDragging = true;
                e.consume();
            }
        });

        setOnMouseDragged(e -> {
            if (isDragging && isSelected) {
                setLayoutX(startLayoutX + e.getSceneX() - dragStartX);
                setLayoutY(startLayoutY + e.getSceneY() - dragStartY);
                e.consume();
            }
        });

        setOnMouseReleased(e -> isDragging = false);
    }

    private void enterEditMode() {
        setEditable(true);
        setStyle("-fx-background-color: white; -fx-border-color: #0078ff; -fx-border-width: 2; -fx-font-size: 14;");
        requestFocus();
        selectAll();
        hideResizeHandles();
    }

    public void selectTextField() {
        isSelected = true;
        setCursor(Cursor.OPEN_HAND);
        setStyle("-fx-background-color: rgba(230,230,230,0.8); -fx-border-color: #0078ff; -fx-border-width: 2; -fx-font-size: 14;");
        showResizeHandles();
        setEditable(false);
    }

    public void deselectTextField() {
        isSelected = false;
        isDragging = false;
        setCursor(Cursor.DEFAULT);
        setStyle("-fx-background-color: rgba(230,230,230,0.8); -fx-border-color: transparent; -fx-font-size: 14;");
        hideResizeHandles();
        setEditable(false);
    }

    private void setupResizeHandles() {
        topLeft = createHandle(Cursor.NW_RESIZE);
        topRight = createHandle(Cursor.NE_RESIZE);
        bottomLeft = createHandle(Cursor.SW_RESIZE);
        bottomRight = createHandle(Cursor.SE_RESIZE);
        hideResizeHandles();
    }

    private Rectangle createHandle(Cursor cursorType) {
        Rectangle handle = new Rectangle(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE);
        handle.setStyle("-fx-fill: white; -fx-stroke: #0078ff; -fx-stroke-width: 1.5;");
        handle.setCursor(cursorType);

        final double[] startX = new double[1];
        final double[] startY = new double[1];
        final double[] startWidth = new double[1];
        final double[] startHeight = new double[1];
        final double[] startLayoutX = new double[1];
        final double[] startLayoutY = new double[1];

        handle.setOnMousePressed(e -> {
            startX[0] = e.getSceneX();
            startY[0] = e.getSceneY();
            startWidth[0] = getWidth();
            startHeight[0] = getHeight();
            startLayoutX[0] = getLayoutX();
            startLayoutY[0] = getLayoutY();
            e.consume();
        });

        handle.setOnMouseDragged(e -> {
            double deltaX = e.getSceneX() - startX[0];
            double deltaY = e.getSceneY() - startY[0];

            if (cursorType == Cursor.SE_RESIZE) {
                if (startWidth[0] + deltaX > 50) setPrefWidth(startWidth[0] + deltaX);
                if (startHeight[0] + deltaY > 30) setPrefHeight(startHeight[0] + deltaY);
            } else if (cursorType == Cursor.NW_RESIZE) {
                if (startWidth[0] - deltaX > 50) setPrefWidth(startWidth[0] - deltaX);
                if (startHeight[0] - deltaY > 30) setPrefHeight(startHeight[0] - deltaY);
                setLayoutX(startLayoutX[0] + deltaX);
                setLayoutY(startLayoutY[0] + deltaY);
            } else if (cursorType == Cursor.NE_RESIZE) {
                if (startWidth[0] + deltaX > 50) setPrefWidth(startWidth[0] + deltaX);
                if (startHeight[0] - deltaY > 30) setPrefHeight(startHeight[0] - deltaY);
                setLayoutY(startLayoutY[0] + deltaY);
            } else if (cursorType == Cursor.SW_RESIZE) {
                if (startWidth[0] - deltaX > 50) setPrefWidth(startWidth[0] - deltaX);
                if (startHeight[0] + deltaY > 30) setPrefHeight(startHeight[0] + deltaY);
                setLayoutX(startLayoutX[0] + deltaX);
            }
            e.consume();
        });

        return handle;
    }

    public void showResizeHandles() {
        if (getParent() instanceof Group) {
            parentGroup = (Group) getParent();
        }

        if (parentGroup != null && !parentGroup.getChildren().contains(topLeft)) {
            parentGroup.getChildren().addAll(topLeft, topRight, bottomLeft, bottomRight);
            updateHandlesPosition();
        }
    }

    public void hideResizeHandles() {
        if (parentGroup != null) {
            parentGroup.getChildren().removeAll(topLeft, topRight, bottomLeft, bottomRight);
        }
    }

    public void updateHandlesPosition() {
        if (topLeft == null || parentGroup == null) return;

        double x = getLayoutX();
        double y = getLayoutY();
        double w = getWidth();
        double h = getHeight();

        topLeft.setX(x - RESIZE_HANDLE_SIZE / 2);
        topLeft.setY(y - RESIZE_HANDLE_SIZE / 2);

        topRight.setX(x + w - RESIZE_HANDLE_SIZE / 2);
        topRight.setY(y - RESIZE_HANDLE_SIZE / 2);

        bottomLeft.setX(x - RESIZE_HANDLE_SIZE / 2);
        bottomLeft.setY(y + h - RESIZE_HANDLE_SIZE / 2);

        bottomRight.setX(x + w - RESIZE_HANDLE_SIZE / 2);
        bottomRight.setY(y + h - RESIZE_HANDLE_SIZE / 2);
    }

    @Override
    protected void finalize() throws Throwable {
        hideResizeHandles();
        super.finalize();
    }
}