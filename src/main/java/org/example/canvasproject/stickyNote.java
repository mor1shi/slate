package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.UUID;

public class stickyNote extends VBox implements Selectable {

    private double posX, posY;
    private double noteWidth  = 200;
    private double noteHeight = 160;

    private Color noteColor;
    private double currentFontSize = 14.0;

    private final HBox      header;
    private final TextField titleField;
    private final TextArea  bodyArea;

    private boolean editMode = false;
    private SelectionOverlay activeOverlay;

    public stickyNote(double x, double y, Color color) {
        super(0);
        this.posX      = x;
        this.posY      = y;
        this.noteColor = color;

        super.setId(UUID.randomUUID().toString());

        setLayoutX(x);
        setLayoutY(y);
        setPrefWidth(noteWidth);
        setPrefHeight(noteHeight);
        applyNoteStyle();

        header     = buildHeader();
        titleField = buildTitleField();
        bodyArea   = buildBodyArea();

        header.getChildren().add(titleField);
        HBox.setHgrow(titleField, Priority.ALWAYS);

        getChildren().addAll(header, bodyArea);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        setCursor(Cursor.HAND);
        applyFontSizes();

        widthProperty().addListener((o, ov, nv)  -> { if (activeOverlay != null) activeOverlay.refresh(); });
        heightProperty().addListener((o, ov, nv) -> { if (activeOverlay != null) activeOverlay.refresh(); });
    }

    public void setFontSize(double size) {
        this.currentFontSize = size;
        applyFontSizes();
    }

    private void applyFontSizes() {
        if (titleField == null || bodyArea == null) return;
        titleField.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; " +
                "-fx-text-fill: " + titleTextColor() + "; " +
                "-fx-font-size: " + Math.max(10, (int)(currentFontSize * 0.9)) + "px; " +
                "-fx-font-weight: bold; " +
                "-fx-prompt-text-fill: derive(" + titleTextColor() + ", 40%);");
        bodyArea.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; " +
                "-fx-text-fill: #2a2a2a; " +
                "-fx-font-size: " + (int)currentFontSize + "px; " +
                "-fx-control-inner-background: transparent;");
    }

    private HBox buildHeader() {
        HBox h = new HBox(6);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(5, 8, 5, 8));
        h.setStyle("-fx-background-color: " + toHex(noteColor) + "; " +
                "-fx-background-radius: 9 9 0 0;");
        h.setPrefHeight(34);
        return h;
    }

    private TextField buildTitleField() {
        TextField tf = new TextField();
        tf.setPromptText("Заголовок...");
        tf.setEditable(false);
        tf.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                tf.setEditable(true);
                tf.requestFocus();
                tf.selectAll();
                e.consume();
            }
        });
        tf.focusedProperty().addListener((o, was, is) -> {
            if (!is) tf.setEditable(false);
        });
        tf.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE || e.getCode() == KeyCode.ENTER) {
                tf.setEditable(false);
                e.consume();
            }
        });
        return tf;
    }

    private TextArea buildBodyArea() {
        TextArea ta = new TextArea();
        ta.setPromptText("Введите заметку...");
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPadding(new Insets(6, 8, 6, 8));
        ta.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !editMode) {
                enterBodyEditMode();
                e.consume();
            }
        });
        ta.focusedProperty().addListener((o, was, is) -> {
            if (!is && editMode) exitEditMode();
        });
        ta.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) exitEditMode();
        });
        return ta;
    }

    private void enterBodyEditMode() {
        editMode = true;
        bodyArea.setEditable(true);
        bodyArea.requestFocus();
        bodyArea.positionCaret(bodyArea.getText().length());
    }

    @Override
    public String getElementId() { return getId(); }

    @Override public String getType() { return "note"; }
    @Override public Node   getNode() { return this; }

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
        double oldW = getPrefWidth(), oldH = getPrefHeight();

        double w = Math.max(newWidth, 140);
        double h = Math.max(newHeight, 100);
        double newX = oldX, newY = oldY;

        switch (anchor) {
            case "NW": newX = oldX + oldW - w; newY = oldY + oldH - h; break;
            case "NE": newX = oldX;            newY = oldY + oldH - h; break;
            case "SW": newX = oldX + oldW - w; newY = oldY;            break;
            default:   newX = oldX;            newY = oldY;            break;
        }

        setLayoutX(newX);
        setLayoutY(newY);
        setPrefWidth(w);
        setPrefHeight(h);
        if (activeOverlay != null) activeOverlay.refresh();
    }

    @Override public boolean isResizable()      { return true; }
    @Override public boolean supportsEditMode() { return true; }

    @Override
    public void enterEditMode() { enterBodyEditMode(); }

    @Override
    public void exitEditMode() {
        editMode = false;
        bodyArea.setEditable(false);
        titleField.setEditable(false);
        if (activeOverlay != null) activeOverlay.refresh();
    }

    public void setActiveOverlay(SelectionOverlay overlay) {
        this.activeOverlay = overlay;
    }

    public boolean isEditMode() { return editMode; }


    public String getText() { return getNoteText(); }

    public void setText(String text) { setNoteText(text); }

    public Color  getNoteColor() { return noteColor; }

    public String getNoteText() {
        return bodyArea != null ? bodyArea.getText() : "";
    }

    public void setNoteText(String text) {
        if (bodyArea != null) bodyArea.setText(text);
    }

    private static class State implements ElementMemento {
        final double x, y, w, h;
        final String title, body;
        final Color  color;
        final double fontSize;
        State(double x, double y, double w, double h,
              String title, String body, Color color, double fontSize) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.title = title; this.body = body;
            this.color = color; this.fontSize = fontSize;
        }
    }

    @Override
    public ElementMemento save() {
        return new State(
                getLayoutX(), getLayoutY(),
                getPrefWidth(), getPrefHeight(),
                titleField.getText(), bodyArea.getText(),
                noteColor, currentFontSize
        );
    }

    @Override
    public void restore(ElementMemento m) {
        State s = (State) m;
        setLayoutX(s.x); setLayoutY(s.y);
        setPrefWidth(s.w); setPrefHeight(s.h);
        titleField.setText(s.title);
        bodyArea.setText(s.body);
        noteColor = s.color;
        currentFontSize = s.fontSize;
        applyColor();
        applyFontSizes();
        if (activeOverlay != null) activeOverlay.refresh();
    }

    private void applyNoteStyle() {
        setStyle("-fx-background-color: derive(" + toHex(noteColor) + ", 75%); " +
                "-fx-background-radius: 10; " +
                "-fx-border-color: derive(" + toHex(noteColor) + ", -20%); " +
                "-fx-border-radius: 10; " +
                "-fx-border-width: 1; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 8, 0, 0, 3);");
    }

    private String titleTextColor() {
        double lum = 0.299 * noteColor.getRed()
                   + 0.587 * noteColor.getGreen()
                   + 0.114 * noteColor.getBlue();
        return lum > 0.55 ? "#222222" : "#ffffff";
    }

    private void applyColor() {
        applyNoteStyle();
        header.setStyle("-fx-background-color: " + toHex(noteColor) + "; " +
                "-fx-background-radius: 9 9 0 0;");
        applyFontSizes();
    }

    private String toHex(Color c) {
        if (c == null) return "#FFD60A";
        return String.format("#%02X%02X%02X",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }
}
