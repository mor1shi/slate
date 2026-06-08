package org.example.canvasproject;

// Единая нижняя панель инструментов.

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ToolbarPanel extends HBox {

    // Режимы инструментов — по одному на кнопку панели
    public enum ToolMode { PEN, TEXT, FIGURE, NOTE, NONE }

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

    private static final String PANEL_BG =
        "-fx-background-color: #1e1e2e; " +
        "-fx-background-radius: 12; " +
        "-fx-border-color: #3a3a5c; " +
        "-fx-border-radius: 12; " +
        "-fx-border-width: 1;";

    private static final String SEPARATOR_STYLE =
        "-fx-background-color: #3a3a5c;";

    private static final String LABEL_STYLE =
        "-fx-text-fill: #8888aa; -fx-font-size: 11;";

    public ToolbarPanel() {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(0);
        setPadding(new Insets(8, 16, 8, 16));
        setStyle(PANEL_BG);
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
            default     -> new ArrayList<>();
        };

        getChildren().addAll(controls);

        // Анимация появления
        setVisible(true);
        setManaged(true);
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

        // Метка группы
        nodes.add(makeLabel("КИСТЬ"));
        nodes.add(makeSpacer(12));

        // Цвет
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
        // ColorPicker для произвольного цвета
        ColorPicker cp = makeColorPicker(currentColor, c -> {
            currentColor = c;
            if (onColorChanged != null) onColorChanged.accept(c);
        });
        colorRow.getChildren().add(cp);
        nodes.add(colorRow);

        nodes.add(makeSep());

        // Толщина
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

        // Прозрачность
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

    // TEXT — размер шрифта, цвет текста
    private List<Node> buildTextControls() {
        List<Node> nodes = new ArrayList<>();

        nodes.add(makeLabel("ТЕКСТ"));
        nodes.add(makeSpacer(12));

        // Размер шрифта
        nodes.add(makeLabel("Размер"));
        nodes.add(makeSpacer(6));

        // Пресеты размеров
        HBox sizeRow = new HBox(4);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        int[] sizes = {10, 12, 14, 18, 24, 32, 48};
        for (int s : sizes) {
            Button btn = new Button(String.valueOf(s));
            btn.setStyle(sizePresetStyle(s == (int) currentFontSize));
            btn.setPrefSize(34, 28);
            btn.setOnAction(e -> {
                currentFontSize = s;
                if (onFontSizeChanged != null) onFontSizeChanged.accept((double) s);
                sizeRow.getChildren().forEach(n -> {
                    if (n instanceof Button b)
                        b.setStyle(sizePresetStyle(b.getText().equals(String.valueOf(s))));
                });
            });
            sizeRow.getChildren().add(btn);
        }
        nodes.add(sizeRow);

        nodes.add(makeSep());

        // Цвет текста
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

    // FIGURE — выбор фигуры и цвет заливки
    private List<Node> buildFigureControls() {
        List<Node> nodes = new ArrayList<>();

        nodes.add(makeLabel("ФИГУРЫ"));
        nodes.add(makeSpacer(12));

        // Выбор фигуры
        nodes.add(makeLabel("Фигура"));
        nodes.add(makeSpacer(6));

        HBox figureRow = new HBox(6);
        figureRow.setAlignment(Pos.CENTER_LEFT);

        // Пары: id → нода + подпись
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
            lbl.setStyle("-fx-text-fill: #ccccdd; -fx-font-size: 9;");
            btnContent.getChildren().addAll(opt.icon(), lbl);

            Button btn = new Button();
            btn.setGraphic(btnContent);
            btn.setStyle(figurePresetStyle(opt.id().equals(currentFigure)));
            btn.setPrefSize(64, 56);
            btn.setOnAction(e -> {
                currentFigure = opt.id();
                if (onFigureSelected != null) onFigureSelected.accept(opt.id());
                figureBtns.forEach(b -> b.setStyle(figurePresetStyle(false)));
                btn.setStyle(figurePresetStyle(true));
            });
            figureBtns.add(btn);
            figureRow.getChildren().add(btn);
        }
        nodes.add(figureRow);

        nodes.add(makeSep());

        // Цвет фигуры
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

    // NOTE
    private List<Node> buildNoteControls() {
        List<Node> nodes = new ArrayList<>();

        nodes.add(makeLabel("СТИКЕР"));
        nodes.add(makeSpacer(12));

        Label hint = new Label("Кликни на холст чтобы разместить стикер");
        hint.setStyle("-fx-text-fill: #8888aa; -fx-font-size: 12;");
        nodes.add(hint);

        nodes.add(makeSep());

        // Выбор цвета стикера
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

    // Иконки фигур
    private Node makeShapeIcon(String type) {
        String stroke = "#aaaacc";
        String fill   = "transparent";
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
                // 5-конечная звезда
                double[] pts = buildStarPoints(11, 8, 10, 4, 5);
                Polygon star = new Polygon(pts);
                star.setFill(Color.TRANSPARENT);
                star.setStroke(Color.web(stroke)); star.setStrokeWidth(1.5);
                yield star;
            }
            case "arrow" -> {
                // Упрощённая стрелка вправо
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
        l.setStyle(LABEL_STYLE);
        return l;
    }

    private Label makeValueLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccccdd; -fx-font-size: 11; -fx-min-width: 30;");
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
        sep.setStyle(SEPARATOR_STYLE);
        HBox.setMargin(sep, new Insets(0, 12, 0, 12));
        return sep;
    }

    private Button makeColorDot(Color color, Runnable onSelect) {
        Button btn = new Button();
        btn.setShape(new Circle(9));
        btn.setPrefSize(18, 18);
        btn.setMinSize(18, 18);

        if (color == Color.TRANSPARENT) {
            btn.setStyle("-fx-background-color: " +
                "linear-gradient(from 0% 0% to 50% 50%, #bbb 25%, transparent 25%)," +
                "linear-gradient(from 50% 50% to 100% 100%, #bbb 25%, transparent 25%)," +
                "white; -fx-border-color: #555; -fx-border-width: 1;");
        } else {
            String hex = toHex(color);
            String border = color.equals(Color.WHITE) ? "#aaa" : hex;
            btn.setStyle("-fx-background-color: " + hex + "; " +
                "-fx-border-color: " + border + "; -fx-border-width: 1;");
        }

        btn.setOnMouseEntered(e -> btn.setScaleX(1.2));
        btn.setOnMouseExited(e  -> btn.setScaleX(1.0));
        btn.setOnAction(e -> onSelect.run());
        return btn;
    }

    private ColorPicker makeColorPicker(Color initial, Consumer<Color> onChange) {
        ColorPicker cp = new ColorPicker(initial);
        cp.setPrefSize(28, 28);
        cp.setStyle("-fx-color-label-visible: false; -fx-background-color: transparent;");
        cp.setOnAction(e -> onChange.accept(cp.getValue()));
        return cp;
    }

    private Slider makeSlider(double min, double max, double value, Consumer<Double> onChange) {
        Slider s = new Slider(min, max, value);
        s.setStyle("-fx-control-inner-background: #3a3a5c;");
        s.valueProperty().addListener((o, oldV, newV) -> onChange.accept(newV.doubleValue()));
        return s;
    }

    private String sizePresetStyle(boolean active) {
        String bg    = active ? "#0078ff" : "#2a2a3e";
        String border= active ? "#0078ff" : "#3a3a5c";
        return "-fx-background-color: " + bg + "; " +
               "-fx-text-fill: " + (active ? "white" : "#ccccdd") + "; " +
               "-fx-border-color: " + border + "; " +
               "-fx-border-radius: 5; -fx-background-radius: 5; -fx-font-size: 11;";
    }

    private String figurePresetStyle(boolean active) {
        String bg    = active ? "#1a3a5c" : "#2a2a3e";
        String border= active ? "#0078ff" : "#3a3a5c";
        return "-fx-background-color: " + bg + "; " +
               "-fx-border-color: " + border + "; " +
               "-fx-border-radius: 7; -fx-background-radius: 7; " +
               "-fx-border-width: 1.5;";
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
