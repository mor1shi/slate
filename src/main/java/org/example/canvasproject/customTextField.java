package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;

import java.util.UUID;

public class customTextField extends TextField implements Selectable {

    private final String id = UUID.randomUUID().toString();
    private boolean editMode = false;
    private SelectionOverlay activeOverlay;

    private double currentFontSize = 14.0;
    private Color currentFontColor = Color.BLACK;

    public customTextField(double x, double y) {
        super();
        setLayoutX(x);
        setLayoutY(y);
        setPrefWidth(150);
        setPrefHeight(40);
        setPromptText("Введите текст...");
        setCursor(Cursor.HAND);
        setEditable(false);

        // Применяем начальный стиль
        applyNormalStyle();

        widthProperty().addListener((obs, old, val) -> { if (activeOverlay != null) activeOverlay.refresh(); });
        heightProperty().addListener((obs, old, val) -> { if (activeOverlay != null) activeOverlay.refresh(); });

        focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && editMode) exitEditMode();
        });
    }

    // Методы для обновления размера и цвета шрифта из контроллера
    public void setFontSize(double size) {
        this.currentFontSize = size;
        if (editMode) applyEditStyle();
        else applyNormalStyle();
    }

    public void setFontColor(Color color) {
        this.currentFontColor = color;
        if (editMode) applyEditStyle();
        else applyNormalStyle();
    }

    private void applyNormalStyle() {
        String hex = toHex(currentFontColor);
        setStyle("-fx-background-color: rgba(230,230,230,0.85); " +
                "-fx-border-color: #0078ff; " +
                "-fx-border-width: 1; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4; " +
                "-fx-font-size: " + (int)currentFontSize + "px; " +
                "-fx-text-fill: " + hex + ";");
    }

    private void applyEditStyle() {
        String hex = toHex(currentFontColor);
        setStyle("-fx-background-color: white; " +
                "-fx-border-color: #0078ff; " +
                "-fx-border-width: 2; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4; " +
                "-fx-font-size: " + (int)currentFontSize + "px; " +
                "-fx-text-fill: " + hex + ";");
    }

    private String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }

    public void setActiveOverlay(SelectionOverlay overlay) {
        this.activeOverlay = overlay;
    }

    public boolean isEditMode() { return editMode; }

    @Override public String getElementId() { return id; }
    @Override public String getType() { return "text"; }
    @Override public Node getNode() { return this; }

    @Override
    public Bounds getSelectBounds() {
        return new BoundingBox(getLayoutX(), getLayoutY(), getWidth(), getHeight());
    }

    @Override
    public void move(double dx, double dy) {
        setLayoutX(getLayoutX() + dx);
        setLayoutY(getLayoutY() + dy);
        if (activeOverlay != null) activeOverlay.refresh();
    }

    @Override
    public void resize(String anchor, double newWidth, double newHeight) {
        double oldX = getLayoutX();
        double oldY = getLayoutY();
        double oldW = getPrefWidth();
        double oldH = getPrefHeight();

        double w = Math.max(newWidth, 80);
        double h = Math.max(newHeight, 30);
        double newX = oldX;
        double newY = oldY;

        switch (anchor) {
            case "NW":
                newX = oldX + oldW - w;
                newY = oldY + oldH - h;
                break;
            case "NE":
                newX = oldX;
                newY = oldY + oldH - h;
                break;
            case "SW":
                newX = oldX + oldW - w;
                newY = oldY;
                break;
            case "SE":
            default:
                newX = oldX;
                newY = oldY;
                break;
        }

        setLayoutX(newX);
        setLayoutY(newY);
        setPrefWidth(w);
        setPrefHeight(h);
        if (activeOverlay != null) activeOverlay.refresh();
    }

    @Override public boolean isResizable() { return true; }
    @Override public boolean supportsEditMode() { return true; }

    @Override
    public void enterEditMode() {
        if (editMode) return;
        editMode = true;
        setEditable(true);
        applyEditStyle();
        requestFocus();
        selectAll();
        setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE && editMode) exitEditMode(); });
    }

    @Override
    public void exitEditMode() {
        if (!editMode) return;
        editMode = false;
        setEditable(false);
        applyNormalStyle();
        setOnKeyPressed(null);
        if (activeOverlay != null) activeOverlay.refresh();
    }

    private static class State implements ElementMemento {
        final double x, y, w, h;
        final String text;
        final double fontSize;
        final Color fontColor;
        State(double x, double y, double w, double h, String text, double fontSize, Color fontColor) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.text = text;
            this.fontSize = fontSize;
            this.fontColor = fontColor;
        }
    }

    @Override
    public ElementMemento save() {
        double w = getPrefWidth();
        double h = getPrefHeight();
        return new State(getLayoutX(), getLayoutY(), w, h, getText(), currentFontSize, currentFontColor);
    }

    @Override
    public void restore(ElementMemento memento) {
        State s = (State) memento;
        setLayoutX(s.x);
        setLayoutY(s.y);
        setPrefWidth(s.w);
        setPrefHeight(s.h);
        setText(s.text);
        currentFontSize = s.fontSize;
        currentFontColor = s.fontColor;
        if (editMode) applyEditStyle();
        else applyNormalStyle();
        if (activeOverlay != null) activeOverlay.refresh();
    }
}