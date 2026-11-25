package org.example.canvasproject;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ToolbarPanel extends HBox {

    public enum ToolMode { PEN, TEXT, FIGURE, NOTE, SELECT, NONE }

    private Consumer<Color>  onColorChanged;
    private Consumer<Double> onLineWidthChanged;
    private Consumer<Double> onOpacityChanged;
    private Consumer<Double> onFontSizeChanged;
    private Consumer<Color>  onFontColorChanged;
    private Consumer<String> onFigureSelected;

    private Color  currentColor    = Color.BLACK;
    private double currentWidth    = 4.0;
    private double currentOpacity  = 1.0;
    private double currentFontSize = 14.0;
    private Color  currentFontColor = Color.BLACK;
    private String currentFigure   = "rect";

    private ToolMode activeMode = ToolMode.NONE;
    private Label selectionCountLabel;
    private Label selectionColorMixed;
    private Label selectionWidthValue;
    private Label selectionWidthMixed;
    private Label selectionOpacityValue;
    private Label selectionOpacityMixed;
    private final List<Node> selectionColorNodes = new ArrayList<>();
    private final List<Node> selectionWidthNodes = new ArrayList<>();
    private final List<Node> selectionOpacityNodes = new ArrayList<>();

    public ToolbarPanel() {
        getStyleClass().add("tool-panel");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(0);
        setPadding(new Insets(10, 14, 10, 14));
        setVisible(false);
        setManaged(false);
        setOpacity(0);
    }

    public void show(ToolMode mode) {
        if (activeMode == mode) {
            hide();
            return;
        }
        activeMode = mode;
        getChildren().clear();

        List<Node> controls = switch (mode) {
            case PEN    -> buildPenControls();
            case TEXT   -> buildTextControls();
            case FIGURE -> buildFigureControls();
            case NOTE   -> buildNoteControls();
            case SELECT -> buildSelectControls();
            default     -> new ArrayList<>();
        };

        getChildren().addAll(controls);

        setVisible(true);
        setManaged(true);
        applyCss();
        layout();

        FadeTransition ft = new FadeTransition(Duration.millis(150), this);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();
    }

    public void hide() {
        activeMode = ToolMode.NONE;
        FadeTransition ft = new FadeTransition(Duration.millis(100), this);
        ft.setFromValue(getOpacity());
        ft.setToValue(0);
        ft.setOnFinished(e -> { setVisible(false); setManaged(false); });
        ft.play();
    }

    public ToolMode getActiveMode() { return activeMode; }

    private List<Node> buildPenControls() {
        List<Node> nodes = new ArrayList<>();

        nodes.add(makeLabel("Цвет"));
        nodes.add(makeSpacer(6));
        HBox colorRow = new HBox(5);
        colorRow.setAlignment(Pos.CENTER_LEFT);

        Color[] palette = {
                Color.BLACK, Color.WHITE, Color.web("#FF3B30"), Color.web("#FF9500"),
                Color.web("#FFCC00"), Color.web("#34C759"), Color.web("#007AFF"),
                Color.web("#AF52DE"), Color.web("#FF2D55"), Color.LIGHTGRAY
        };
        for (Color c : palette) {
            colorRow.getChildren().add(makeColorDot(c, () -> {
                currentColor = c;
                if (onColorChanged != null) onColorChanged.accept(c);
            }));
        }
        ColorPicker cp = makeColorPicker(currentColor, c -> {
            currentColor = c;
            if (onColorChanged != null) onColorChanged.accept(c);
        });
        colorRow.getChildren().add(cp);
        nodes.add(colorRow);

        nodes.add(makeSep());

        nodes.add(makeLabel("Толщина"));
        nodes.add(makeSpacer(6));
        Slider widthSlider = makeSlider(1, 30, currentWidth, val -> {
            currentWidth = val;
            if (onLineWidthChanged != null) onLineWidthChanged.accept(val);
        });
        widthSlider.setPrefWidth(100);
        Label widthVal = makeValueLabel(String.format("%.0fpx", currentWidth));
        widthSlider.valueProperty().addListener((o, oldV, v) ->
                widthVal.setText(String.format("%.0fpx", v.doubleValue())));
        nodes.add(widthSlider);
        nodes.add(makeSpacer(4));
        nodes.add(widthVal);

        nodes.add(makeSep());

        nodes.add(makeLabel("Прозрачность"));
        nodes.add(makeSpacer(6));
        Slider opacitySlider = makeSlider(0.05, 1.0, currentOpacity, val -> {
            currentOpacity = val;
            if (onOpacityChanged != null) onOpacityChanged.accept(val);
        });
        opacitySlider.setPrefWidth(90);
        Label opVal = makeValueLabel(String.format("%.0f%%", currentOpacity * 100));
        opacitySlider.valueProperty().addListener((o, oldV, v) ->
                opVal.setText(String.format("%.0f%%", v.doubleValue() * 100)));
        nodes.add(opacitySlider);
        nodes.add(makeSpacer(4));
        nodes.add(opVal);

        return nodes;
    }

    private List<Node> buildSelectControls() {
        List<Node> nodes = new ArrayList<>();
        selectionColorNodes.clear();
        selectionWidthNodes.clear();
        selectionOpacityNodes.clear();

        selectionCountLabel = makeLabel(org.example.canvasproject.i18n.I18n.t("selection.count", 0));
        nodes.add(selectionCountLabel);

        // Цвет есть не у всех типов (например, у стикера своего сеттера нет),
        // поэтому строку можно скрыть, если общих свойств у выделения нет.
        addTo(nodes, selectionColorNodes, makeSep());
        addTo(nodes, selectionColorNodes, makeLabel(org.example.canvasproject.i18n.I18n.t("selection.color")));
        addTo(nodes, selectionColorNodes, makeSpacer(6));
        HBox colorRow = new HBox(5);
        colorRow.setAlignment(Pos.CENTER_LEFT);
        Color[] palette = {
                Color.BLACK, Color.WHITE, Color.web("#FF3B30"), Color.web("#FF9500"),
                Color.web("#FFCC00"), Color.web("#34C759"), Color.web("#0078FF"),
                Color.web("#AF52DE"), Color.web("#FF2D55"), Color.LIGHTGRAY
        };
        for (Color c : palette) {
            colorRow.getChildren().add(makeColorDot(c, () -> {
                currentColor = c;
                if (onColorChanged != null) onColorChanged.accept(c);
            }));
        }
        colorRow.getChildren().add(makeColorPicker(currentColor, c -> {
            currentColor = c;
            if (onColorChanged != null) onColorChanged.accept(c);
        }));
        nodes.add(colorRow);
        selectionColorNodes.add(colorRow);
        selectionColorMixed = makeMixedLabel();
        nodes.add(selectionColorMixed);
        selectionColorNodes.add(selectionColorMixed);

        addTo(nodes, selectionWidthNodes, makeSep());

        addTo(nodes, selectionWidthNodes, makeLabel(org.example.canvasproject.i18n.I18n.t("selection.width")));
        addTo(nodes, selectionWidthNodes, makeSpacer(6));
        Slider widthSlider = makeSlider(1, 30, currentWidth, val -> {
            currentWidth = val;
            if (onLineWidthChanged != null) onLineWidthChanged.accept(val);
        });
        widthSlider.setPrefWidth(90);
        selectionWidthValue = makeValueLabel(String.format("%.0fpx", currentWidth));
        widthSlider.valueProperty().addListener((o, oldV, v) ->
                selectionWidthValue.setText(String.format("%.0fpx", v.doubleValue())));
        nodes.add(widthSlider);
        selectionWidthNodes.add(widthSlider);
        nodes.add(makeSpacer(4));
        selectionWidthNodes.add(nodes.get(nodes.size() - 1));
        nodes.add(selectionWidthValue);
        selectionWidthNodes.add(selectionWidthValue);
        selectionWidthMixed = makeMixedLabel();
        nodes.add(selectionWidthMixed);
        selectionWidthNodes.add(selectionWidthMixed);

        addTo(nodes, selectionOpacityNodes, makeSep());

        addTo(nodes, selectionOpacityNodes, makeLabel(org.example.canvasproject.i18n.I18n.t("selection.opacity")));
        addTo(nodes, selectionOpacityNodes, makeSpacer(6));
        Slider opacitySlider = makeSlider(0.05, 1.0, currentOpacity, val -> {
            currentOpacity = val;
            if (onOpacityChanged != null) onOpacityChanged.accept(val);
        });
        opacitySlider.setPrefWidth(80);
        selectionOpacityValue = makeValueLabel(String.format("%.0f%%", currentOpacity * 100));
        opacitySlider.valueProperty().addListener((o, oldV, v) ->
                selectionOpacityValue.setText(String.format("%.0f%%", v.doubleValue() * 100)));
        nodes.add(opacitySlider);
        selectionOpacityNodes.add(opacitySlider);
        nodes.add(makeSpacer(4));
        selectionOpacityNodes.add(nodes.get(nodes.size() - 1));
        nodes.add(selectionOpacityValue);
        selectionOpacityNodes.add(selectionOpacityValue);
        selectionOpacityMixed = makeMixedLabel();
        nodes.add(selectionOpacityMixed);
        selectionOpacityNodes.add(selectionOpacityMixed);

        return nodes;
    }

    private void addTo(List<Node> all, List<Node> group, Node node) {
        all.add(node);
        group.add(node);
    }

    private void setGroupVisible(List<Node> group, boolean visible) {
        for (Node node : group) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }

    private Label makeMixedLabel() {
        Label label = makeLabel(org.example.canvasproject.i18n.I18n.t("selection.mixed"));
        label.setVisible(false);
        label.setManaged(false);
        return label;
    }

    /** Обновляет счётчик и пометки «разные» у уже открытой панели выделения. */
    public void updateSelectionSummary(int count, boolean mixedColor,
                                       boolean mixedWidth, boolean mixedOpacity,
                                       boolean hasColor, boolean hasWidth) {
        if (activeMode != ToolMode.SELECT) return;
        if (selectionCountLabel != null) {
            selectionCountLabel.setText(org.example.canvasproject.i18n.I18n.t("selection.count", count));
        }
        // Показываем только общие для всех выбранных типов свойства.
        setGroupVisible(selectionColorNodes, hasColor);
        setGroupVisible(selectionWidthNodes, hasWidth);
        setMixed(selectionColorMixed, mixedColor);
        setMixed(selectionWidthMixed, mixedWidth);
        setMixed(selectionOpacityMixed, mixedOpacity);
        if (mixedWidth && selectionWidthValue != null) {
            selectionWidthValue.setText(org.example.canvasproject.i18n.I18n.t("selection.mixed"));
        }
        if (mixedOpacity && selectionOpacityValue != null) {
            selectionOpacityValue.setText(org.example.canvasproject.i18n.I18n.t("selection.mixed"));
        }
    }

    private void setMixed(Label label, boolean mixed) {
        if (label == null) return;
        label.setVisible(mixed);
        label.setManaged(mixed);
    }

    private List<Node> buildTextControls() {
        List<Node> nodes = new ArrayList<>();

        nodes.add(makeLabel("Размер"));
        nodes.add(makeSpacer(6));

        HBox sizeRow = new HBox(4);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        int[] sizes = {10, 12, 14, 18, 24, 32, 48};
        for (int s : sizes) {
            Button btn = new Button(String.valueOf(s));
            btn.getStyleClass().add("tool-chip");
            setChipActive(btn, s == (int) currentFontSize);
            btn.setPrefSize(34, 28);
            btn.setOnAction(e -> {
                currentFontSize = s;
                if (onFontSizeChanged != null) onFontSizeChanged.accept((double) s);
                sizeRow.getChildren().forEach(n -> {
                    if (n instanceof Button b)
                        setChipActive(b, b.getText().equals(String.valueOf(s)));
                });
            });
            sizeRow.getChildren().add(btn);
        }
        nodes.add(sizeRow);

        nodes.add(makeSep());

        nodes.add(makeLabel("Цвет"));
        nodes.add(makeSpacer(6));
        HBox colorRow = new HBox(5);
        colorRow.setAlignment(Pos.CENTER_LEFT);
        Color[] textPalette = {
                Color.BLACK, Color.WHITE, Color.web("#FF3B30"),
                Color.web("#007AFF"), Color.web("#34C759"),
                Color.web("#FF9500"), Color.web("#AF52DE"), Color.DARKGRAY
        };
        for (Color c : textPalette) {
            colorRow.getChildren().add(makeColorDot(c, () -> {
                currentFontColor = c;
                if (onFontColorChanged != null) onFontColorChanged.accept(c);
            }));
        }
        ColorPicker cp = makeColorPicker(currentFontColor, c -> {
            currentFontColor = c;
            if (onFontColorChanged != null) onFontColorChanged.accept(c);
        });
        colorRow.getChildren().add(cp);
        nodes.add(colorRow);

        return nodes;
    }

    private List<Node> buildFigureControls() {
        List<Node> nodes = new ArrayList<>();

        HBox figureRow = new HBox(6);
        figureRow.setAlignment(Pos.CENTER_LEFT);

        record FigureOption(String id, Node icon, String label) {}
        List<FigureOption> options = List.of(
                new FigureOption("rect",     makeShapeIcon("rect"),     "Прямоугольник"),
                new FigureOption("circle",   makeShapeIcon("circle"),   "Эллипс"),
                new FigureOption("triangle", makeShapeIcon("triangle"), "Треугольник"),
                new FigureOption("star",     makeShapeIcon("star"),     "Звезда"),
                new FigureOption("arrow",    makeShapeIcon("arrow"),    "Стрелка")
        );

        List<Button> figureBtns = new ArrayList<>();
        for (FigureOption opt : options) {
            VBox btnContent = new VBox(3);
            btnContent.setAlignment(Pos.CENTER);
            Label lbl = new Label(opt.label());
            lbl.getStyleClass().add("figure-btn-label");
            btnContent.getChildren().addAll(opt.icon(), lbl);

            Button btn = new Button();
            btn.setGraphic(btnContent);
            btn.getStyleClass().add("figure-btn");
            setFigureActive(btn, opt.id().equals(currentFigure));
            btn.setPrefSize(64, 56);
            btn.setOnAction(e -> {
                currentFigure = opt.id();
                if (onFigureSelected != null) onFigureSelected.accept(opt.id());
                figureBtns.forEach(b -> setFigureActive(b, false));
                setFigureActive(btn, true);
            });
            figureBtns.add(btn);
            figureRow.getChildren().add(btn);
        }
        nodes.add(figureRow);

        nodes.add(makeSep());

        nodes.add(makeLabel("Цвет"));
        nodes.add(makeSpacer(6));
        HBox colorRow = new HBox(5);
        colorRow.setAlignment(Pos.CENTER_LEFT);
        Color[] palette = {
                Color.web("#007AFF"), Color.web("#34C759"), Color.web("#FF3B30"),
                Color.web("#FF9500"), Color.web("#AF52DE"), Color.WHITE, Color.BLACK,
                Color.TRANSPARENT
        };
        for (Color c : palette) {
            colorRow.getChildren().add(makeColorDot(c, () -> {
                currentColor = c;
                if (onColorChanged != null) onColorChanged.accept(c);
            }));
        }
        ColorPicker cp = makeColorPicker(currentColor, c -> {
            currentColor = c;
            if (onColorChanged != null) onColorChanged.accept(c);
        });
        colorRow.getChildren().add(cp);
        nodes.add(colorRow);

        return nodes;
    }

    private List<Node> buildNoteControls() {
        List<Node> nodes = new ArrayList<>();

        Label hint = new Label("Кликните по холсту, чтобы добавить заметку");
        hint.getStyleClass().add("tool-hint");
        nodes.add(hint);

        nodes.add(makeSep());

        nodes.add(makeLabel("Цвет стикера"));
        nodes.add(makeSpacer(6));
        HBox colorRow = new HBox(5);
        colorRow.setAlignment(Pos.CENTER_LEFT);
        Color[] stickerColors = {
                Color.web("#FFD60A"), Color.web("#FF9500"), Color.web("#FF2D55"),
                Color.web("#34C759"), Color.web("#007AFF"), Color.web("#AF52DE"),
                Color.WHITE
        };
        for (Color c : stickerColors) {
            colorRow.getChildren().add(makeColorDot(c, () -> {
                currentColor = c;
                if (onColorChanged != null) onColorChanged.accept(c);
            }));
        }
        nodes.add(colorRow);

        return nodes;
    }

    private Node makeShapeIcon(String type) {
        String stroke = "#C4C4C4";
        return switch (type) {
            case "rect" -> {
                Rectangle r = new Rectangle(22, 16);
                r.setFill(Color.TRANSPARENT);
                r.setStroke(Color.web(stroke)); r.setStrokeWidth(1.5);
                r.setArcWidth(3); r.setArcHeight(3);
                yield r;
            }
            case "circle" -> {
                Ellipse el = new Ellipse(11, 8);
                el.setFill(Color.TRANSPARENT);
                el.setStroke(Color.web(stroke)); el.setStrokeWidth(1.5);
                yield el;
            }
            case "triangle" -> {
                Polygon p = new Polygon(11.0,0.0, 22.0,16.0, 0.0,16.0);
                p.setFill(Color.TRANSPARENT);
                p.setStroke(Color.web(stroke)); p.setStrokeWidth(1.5);
                yield p;
            }
            case "star" -> {
                double[] pts = buildStarPoints(11, 8, 10, 4, 5);
                Polygon star = new Polygon(pts);
                star.setFill(Color.TRANSPARENT);
                star.setStroke(Color.web(stroke)); star.setStrokeWidth(1.5);
                yield star;
            }
            case "arrow" -> {
                Polygon arr = new Polygon(
                        0.0,5.0,  14.0,5.0,  14.0,1.0,  22.0,8.0,  14.0,15.0,  14.0,11.0,  0.0,11.0
                );
                arr.setFill(Color.TRANSPARENT);
                arr.setStroke(Color.web(stroke)); arr.setStrokeWidth(1.5);
                yield arr;
            }
            default -> new Rectangle(20, 14);
        };
    }

    private double[] buildStarPoints(double cx, double cy,
                                     double outerR, double innerR, int n) {
        double[] pts = new double[n * 4];
        for (int i = 0; i < n; i++) {
            double outerAngle = Math.toRadians(-90 + i * 360.0 / n);
            double innerAngle = outerAngle + Math.toRadians(180.0 / n);
            pts[i*4]   = cx + outerR * Math.cos(outerAngle);
            pts[i*4+1] = cy + outerR * Math.sin(outerAngle);
            pts[i*4+2] = cx + innerR * Math.cos(innerAngle);
            pts[i*4+3] = cy + innerR * Math.sin(innerAngle);
        }
        return pts;
    }

    private Label makeLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("tool-label");
        return l;
    }

    private Label makeValueLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("tool-value");
        return l;
    }

    private Region makeSpacer(double w) {
        Region r = new Region();
        r.setMinWidth(w);
        r.setPrefWidth(w);
        return r;
    }

    private Region makeSep() {
        Region sep = new Region();
        sep.setPrefWidth(1);
        sep.setMinWidth(1);
        sep.setPrefHeight(28);
        sep.getStyleClass().add("tool-sep");
        HBox.setMargin(sep, new Insets(0, 12, 0, 12));
        return sep;
    }

    private Button makeColorDot(Color color, Runnable onSelect) {
        Button btn = new Button();
        btn.setShape(new Circle(9));
        btn.setPrefSize(24, 24);
        btn.setMinSize(24, 24);
        btn.setAccessibleText("Выбрать цвет " + toHex(color));
        btn.getStyleClass().add("color-dot");

        if (color == Color.TRANSPARENT) {
            btn.setStyle("-fx-background-color: " +
                    "linear-gradient(from 0% 0% to 50% 50%, #bbb 25%, transparent 25%)," +
                    "linear-gradient(from 50% 50% to 100% 100%, #bbb 25%, transparent 25%)," +
                    "white;");
        } else {
            String hex = toHex(color);
            btn.setStyle("-fx-background-color: " + hex + ";");
            btn.setAccessibleText("Выбрать цвет " + hex);
        }

        btn.setOnAction(e -> onSelect.run());
        return btn;
    }

    private ColorPicker makeColorPicker(Color initial, Consumer<Color> onChange) {
        ColorPicker cp = new ColorPicker(initial);
        cp.getStyleClass().add("tool-color-picker");
        cp.setPrefSize(26, 26);
        cp.setOnAction(e -> onChange.accept(cp.getValue()));
        return cp;
    }

    private Slider makeSlider(double min, double max, double value, Consumer<Double> onChange) {
        Slider s = new Slider(min, max, value);
        s.getStyleClass().add("tool-slider");
        s.setStyle("-fx-control-inner-background: #3A3A3A;");
        s.valueProperty().addListener((o, oldV, newV) -> onChange.accept(newV.doubleValue()));
        return s;
    }

    private void setChipActive(Button btn, boolean active) {
        btn.getStyleClass().remove("tool-chip-active");
        if (active) btn.getStyleClass().add("tool-chip-active");
    }

    private void setFigureActive(Button btn, boolean active) {
        btn.getStyleClass().remove("figure-btn-active");
        if (active) btn.getStyleClass().add("figure-btn-active");
    }

    private String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }

    public Color   getCurrentColor()     { return currentColor; }
    public double  getCurrentWidth()     { return currentWidth; }
    public double  getCurrentOpacity()   { return currentOpacity; }
    public double  getCurrentFontSize()  { return currentFontSize; }
    public Color   getCurrentFontColor() { return currentFontColor; }
    public String  getCurrentFigure()    { return currentFigure; }

    public void setOnColorChanged(Consumer<Color>  cb) { onColorChanged = cb; }
    public void setOnLineWidthChanged(Consumer<Double> cb) { onLineWidthChanged = cb; }
    public void setOnOpacityChanged(Consumer<Double> cb) { onOpacityChanged = cb; }
    public void setOnFontSizeChanged(Consumer<Double> cb) { onFontSizeChanged = cb; }
    public void setOnFontColorChanged(Consumer<Color>  cb) { onFontColorChanged = cb; }
    public void setOnFigureSelected(Consumer<String>  cb) { onFigureSelected = cb; }
}