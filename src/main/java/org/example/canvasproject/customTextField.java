package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

import java.util.UUID;

public class customTextField extends TextField implements Selectable {

    private final String id = UUID.randomUUID().toString();
    private boolean editMode = false;
    private SelectionOverlay activeOverlay;

    public customTextField(double x, double y) {
        super();
        setLayoutX(x);
        setLayoutY(y);
        setPrefWidth(150);
        setPrefHeight(40);
        setPromptText("Введите текст...");
        setStyle(styleNormal());
        setCursor(Cursor.HAND);
        setEditable(false);   // КЛЮЧЕВОЙ МОМЕНТ: поле не редактируется при клике

        widthProperty().addListener((obs, old, val) -> { if (activeOverlay != null) activeOverlay.refresh(); });
        heightProperty().addListener((obs, old, val) -> { if (activeOverlay != null) activeOverlay.refresh(); });

        focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && editMode) exitEditMode();
        });
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
        double oldX = getLayoutX(), oldY = getLayoutY();
        double oldW = getWidth(), oldH = getHeight();
        setPrefWidth(Math.max(newWidth, 80));
        setPrefHeight(Math.max(newHeight, 30));
        if (anchor.equals("NW") || anchor.equals("SW")) setLayoutX(oldX + (oldW - getPrefWidth()));
        if (anchor.equals("NW") || anchor.equals("NE")) setLayoutY(oldY + (oldH - getPrefHeight()));
        if (activeOverlay != null) activeOverlay.refresh();
    }

    @Override public boolean isResizable() { return true; }
    @Override public boolean supportsEditMode() { return true; }

    @Override
    public void enterEditMode() {
        if (editMode) return;
        editMode = true;
        setEditable(true);
        setStyle(styleEditMode());
        requestFocus();
        selectAll();
        setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE && editMode) exitEditMode(); });
    }

    @Override
    public void exitEditMode() {
        if (!editMode) return;
        editMode = false;
        setEditable(false);
        setStyle(styleNormal());
        setOnKeyPressed(null);
        if (activeOverlay != null) activeOverlay.refresh();
    }

    private static class State implements ElementMemento {
        final double x, y, w, h;
        final String text;
        State(double x, double y, double w, double h, String text) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.text = text;
        }
    }

    @Override
    public ElementMemento save() {
        double w = getWidth() > 0 ? getWidth() : getPrefWidth();
        double h = getHeight() > 0 ? getHeight() : getPrefHeight();
        return new State(getLayoutX(), getLayoutY(), w, h, getText());
    }

    @Override
    public void restore(ElementMemento memento) {
        State s = (State) memento;
        setLayoutX(s.x);
        setLayoutY(s.y);
        setPrefWidth(s.w);
        setPrefHeight(s.h);
        setText(s.text);
        if (activeOverlay != null) activeOverlay.refresh();
    }

    private String styleNormal() {
        return "-fx-background-color: rgba(230,230,230,0.85); " +
                "-fx-border-color: #0078ff; " +
                "-fx-border-width: 1; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4; " +
                "-fx-font-size: 14;";
    }

    private String styleEditMode() {
        return "-fx-background-color: white; " +
                "-fx-border-color: #0078ff; " +
                "-fx-border-width: 2; " +
                "-fx-border-radius: 4; " +
                "-fx-background-radius: 4; " +
                "-fx-font-size: 14;";
    }
}
