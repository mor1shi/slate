package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.UUID;

public class stickyNote extends VBox implements Selectable {

    private final String id = UUID.randomUUID().toString();

    // Положение и размер
    private double posX, posY;
    private double noteWidth  = 200;
    private double noteHeight = 160;

    // Цвет стикера
    private Color noteColor;

    // UI-элементы
    private final HBox     header;
    private final TextField titleField;
    private final TextArea  bodyArea;

    private boolean editMode = false;

    // Ссылка на активный overlay (для auto-refresh при resize поля)
    private SelectionOverlay activeOverlay;

    public stickyNote(double x, double y, Color color) {
        super(0);
        this.posX      = x;
        this.posY      = y;
        this.noteColor = color;

        setLayoutX(x);
        setLayoutY(y);
        setPrefWidth(noteWidth);
        setPrefHeight(noteHeight);
        setStyle(noteStyle());

        // Шапка с цветом
        header = buildHeader();
        titleField = buildTitleField();
        bodyArea   = buildBodyArea();

        header.getChildren().add(titleField);
        HBox.setHgrow(titleField, Priority.ALWAYS);

        getChildren().addAll(header, bodyArea);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        // Нода не редактируется при обычном клике
        setCursor(Cursor.HAND);

        // Если размер меняется — обновить overlay
        widthProperty().addListener((o, ov, nv)  -> { if (activeOverlay != null) activeOverlay.refresh(); });
        heightProperty().addListener((o, ov, nv) -> { if (activeOverlay != null) activeOverlay.refresh(); });
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
        tf.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-border-color: transparent; " +
            "-fx-text-fill: " + titleTextColor() + "; " +
            "-fx-font-size: 13; -fx-font-weight: bold; " +
            "-fx-prompt-text-fill: derive(" + titleTextColor() + ", 40%);");

        // Двойной клик по заголовку — редактировать заголовок
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
        ta.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-border-color: transparent; " +
            "-fx-text-fill: #2a2a2a; " +
            "-fx-font-size: 12; " +
            "-fx-control-inner-background: transparent;");
        ta.setPadding(new Insets(6, 8, 6, 8));

        // Двойной клик по телу — редактировать тело
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

    @Override public String getElementId() { return id; }
    @Override public String getType()      { return "note"; }
    @Override public Node   getNode()      { return this; }

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

        double w = Math.max(newWidth,  140);
        double h = Math.max(newHeight, 100);

        setPrefWidth(w);
        setPrefHeight(h);

        if (anchor.equals("NW") || anchor.equals("SW")) setLayoutX(oldX + (oldW - w));
        if (anchor.equals("NW") || anchor.equals("NE")) setLayoutY(oldY + (oldH - h));

        if (activeOverlay != null) activeOverlay.refresh();
    }

    @Override public boolean isResizable()     { return true; }
    @Override public boolean supportsEditMode(){ return true; }

    @Override
    public void enterEditMode() {
        enterBodyEditMode();
    }

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

    // Memento
    private static class State implements ElementMemento {
        final double x, y, w, h;
        final String title, body;
        final Color color;
        State(double x, double y, double w, double h, String title, String body, Color color) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.title = title; this.body = body; this.color = color;
        }
    }

    @Override
    public ElementMemento save() {
        return new State(
            getLayoutX(), getLayoutY(),
            getWidth() > 0 ? getWidth() : getPrefWidth(),
            getHeight() > 0 ? getHeight() : getPrefHeight(),
            titleField.getText(), bodyArea.getText(), noteColor
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
        applyColor();
        if (activeOverlay != null) activeOverlay.refresh();
    }

    private String noteStyle() {
        return "-fx-background-color: derive(" + toHex(noteColor) + ", 75%); " +
               "-fx-background-radius: 10; " +
               "-fx-border-color: derive(" + toHex(noteColor) + ", -20%); " +
               "-fx-border-radius: 10; " +
               "-fx-border-width: 1; " +
               "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 8, 0, 0, 3);";
    }

    private String titleTextColor() {
        double luminance = 0.299 * noteColor.getRed()
                         + 0.587 * noteColor.getGreen()
                         + 0.114 * noteColor.getBlue();
        return luminance > 0.55 ? "#222222" : "#ffffff";
    }

    private void applyColor() {
        setStyle(noteStyle());
        header.setStyle("-fx-background-color: " + toHex(noteColor) + "; " +
                        "-fx-background-radius: 9 9 0 0;");
        titleField.setStyle(
            "-fx-background-color: transparent; -fx-border-color: transparent; " +
            "-fx-text-fill: " + titleTextColor() + "; -fx-font-size: 13; " +
            "-fx-font-weight: bold; " +
            "-fx-prompt-text-fill: derive(" + titleTextColor() + ", 40%);");
    }

    private String toHex(Color c) {
        if (c == null) return "#FFD60A";
        return String.format("#%02X%02X%02X",
            (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }
}
