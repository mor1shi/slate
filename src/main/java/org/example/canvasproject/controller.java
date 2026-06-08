package org.example.canvasproject;

import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.Cursor;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class controller {

    @FXML private Canvas canvas;
    @FXML private Button penBtn, textBtn, figureBtn, imgBtm, noteBtn;
    @FXML private AnchorPane pane;
    @FXML private ImageView imageView;

    private GraphicsContext gc;

    private enum Mode { NONE, PEN, TEXT, FIGURE, NOTE }
    private Mode currentMode = Mode.NONE;

    private Group canvasGroup = new Group();
    private double zoomFactor = 1.0;
    private double translateX = 0, translateY = 0;
    private boolean isPanning = false;
    private double panStartX, panStartY, translateStartX, translateStartY;

    private double lastX, lastY;
    private long lastDrawTime;
    private static final double DISTANCE = 4.0;
    private static final long   DELAY    = 16;

    private final CommandManager commandManager = new CommandManager();
    private BoardModel model;
    private SelectionManager selection;

    private lineElement currentLine = null;

    // Настройки инструментов ToolbarPanel
    private Color  penColor    = Color.BLACK;
    private double penWidth    = 4.0;
    private double penOpacity  = 1.0;
    private double fontSize    = 14.0;
    private Color  fontColor   = Color.BLACK;
    private String figureType  = "rect";
    private Color  figureColor = Color.web("#007AFF");
    private Color  noteColor   = Color.web("#FFD60A");

    private ToolbarPanel toolbarPanel;

    @FXML
    public void initialize() {
        gc = canvas.getGraphicsContext2D();
        Platform.runLater(() -> {
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        });
        canvas.setCache(true);
        canvas.setCacheHint(CacheHint.SPEED);

        canvasGroup.getChildren().add(canvas);
        pane.getChildren().add(0, canvasGroup);

        model     = new BoardModel(canvasGroup);
        selection = new SelectionManager(canvasGroup, model, commandManager);

        // Создаём и размещаем ToolbarPanel внизу по центру AnchorPane
        setupToolbarPanel();

        setupZoomAndPan();
        setupCanvasInteractions();
        setupCentering();
        setupSceneZoom();
        setupHotkeys();

        imgBtm.setOnAction(e -> handleUploadButton());

        // Кнопки панели инструментов
        penBtn.setOnAction(e -> toggleMode(Mode.PEN));
        textBtn.setOnAction(e -> toggleMode(Mode.TEXT));
        figureBtn.setOnAction(e -> toggleMode(Mode.FIGURE));
        noteBtn.setOnAction(e -> toggleMode(Mode.NOTE));

        // Анимация кнопок
        for (Button btn : new Button[]{textBtn, penBtn, figureBtn, imgBtm, noteBtn}) {
            ScaleTransition in  = new ScaleTransition(Duration.millis(100), btn);
            in.setToX(1.2); in.setToY(1.2);
            ScaleTransition out = new ScaleTransition(Duration.millis(100), btn);
            out.setToX(1.0); out.setToY(1.0);
            btn.setOnMouseEntered(ev -> in.playFromStart());
            btn.setOnMouseExited(ev  -> out.playFromStart());
        }
    }

    // ToolbarPanel — создание и подключение колбэков
    private void setupToolbarPanel() {
        toolbarPanel = new ToolbarPanel();

        toolbarPanel.setOnColorChanged(c -> {
            switch (currentMode) {
                case PEN    -> penColor    = c;
                case FIGURE -> figureColor = c;
                case NOTE   -> noteColor   = c;
            }
        });
        toolbarPanel.setOnLineWidthChanged(w -> penWidth   = w);
        toolbarPanel.setOnOpacityChanged(  o -> penOpacity = o);
        toolbarPanel.setOnFontSizeChanged( s -> fontSize   = s);
        toolbarPanel.setOnFontColorChanged(c -> fontColor  = c);
        toolbarPanel.setOnFigureSelected(  f -> figureType = f);

        pane.getChildren().add(toolbarPanel);

        // Динамически пересчитываем позицию при изменении размера окна
        pane.widthProperty().addListener((o, ov, nv) -> repositionToolbar());
        pane.heightProperty().addListener((o, ov, nv) -> repositionToolbar());
    }

    // Позиционировать toolbar над нижней кнопочной панелью
    private void repositionToolbar() {
        // Ждём пока layout посчитается
        Platform.runLater(() -> {
            double panelW = toolbarPanel.prefWidth(-1);
            double x = (pane.getWidth() - panelW) / 2;
            // 100px от низа — над нижней панелью с кнопками
            double y = pane.getHeight() - toolbarPanel.prefHeight(-1) - 100;
            AnchorPane.setLeftAnchor(toolbarPanel, x);
            AnchorPane.setTopAnchor(toolbarPanel, y);
        });
    }

    // Переключение режимов
    private void toggleMode(Mode mode) {
        selection.clearSelection();

        if (currentMode == mode) {
            // Повторное нажатие — выключить режим
            currentMode = Mode.NONE;
            toolbarPanel.hide();
            updateCursor();
            return;
        }

        currentMode = mode;
        updateCursor();

        // Показать нужную панель
        ToolbarPanel.ToolMode toolMode = switch (mode) {
            case PEN    -> ToolbarPanel.ToolMode.PEN;
            case TEXT   -> ToolbarPanel.ToolMode.TEXT;
            case FIGURE -> ToolbarPanel.ToolMode.FIGURE;
            case NOTE   -> ToolbarPanel.ToolMode.NOTE;
            default     -> ToolbarPanel.ToolMode.NONE;
        };
        toolbarPanel.show(toolMode);
        repositionToolbar();
    }

    private void updateCursor() {
        canvas.setCursor(switch (currentMode) {
            case PEN    -> Cursor.CROSSHAIR;
            case TEXT   -> Cursor.TEXT;
            case FIGURE -> Cursor.CROSSHAIR;
            case NOTE   -> Cursor.HAND;
            default     -> Cursor.DEFAULT;
        });
    }

    // Горячие клавиши
    private void setupHotkeys() {
        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            newScene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                // Не перехватываем если печатаем в textField или stickyNote
                if (newScene.getFocusOwner() instanceof customTextField ctf && ctf.isEditMode()) return;
                if (newScene.getFocusOwner() instanceof stickyNote sn && sn.isEditMode()) return;
                if (newScene.getFocusOwner() instanceof javafx.scene.control.TextArea) return;
                if (newScene.getFocusOwner() instanceof javafx.scene.control.TextField tf
                        && tf.isEditable()) return;

                if (e.isControlDown() && e.getCode() == KeyCode.Z) {
                    if (commandManager.undo()) selection.clearSelection();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.Y) {
                    if (commandManager.redo()) selection.clearSelection();
                    e.consume();
                } else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                    selection.deleteSelected();
                    e.consume();
                } else if (e.getCode() == KeyCode.ESCAPE) {
                    if (currentMode != Mode.NONE) {
                        currentMode = Mode.NONE;
                        toolbarPanel.hide();
                        updateCursor();
                    } else {
                        selection.clearSelection();
                    }
                    e.consume();
                }
            });
        });
    }

    private void setupCanvasInteractions() {
        canvas.setOnMousePressed(e -> {
            double x = e.getX();
            double y = e.getY();
            if (e.getButton() != MouseButton.PRIMARY) return;

            switch (currentMode) {

                case TEXT -> {
                    customTextField tf = new customTextField(x, y);
                    tf.setStyle(tf.getStyle() +
                            " -fx-font-size: " + (int)fontSize + "; " +
                            " -fx-text-fill: " + toHex(fontColor) + ";");
                    selection.add(tf);
                }

                case FIGURE -> {
                    figureElement fig = new figureElement(
                            figureType, x - 60, y - 45, 120, 90, figureColor
                    );
                    selection.add(fig);
                }

                case NOTE -> {
                    stickyNote note = new stickyNote(x, y, noteColor);
                    selection.add(note);
                }

                case PEN -> {
                    selection.clearSelection();
                    currentLine = new lineElement(
                            Color.color(penColor.getRed(), penColor.getGreen(),
                                    penColor.getBlue(), penOpacity),
                            penWidth
                    );
                    currentLine.addPoint(x, y);
                    canvasGroup.getChildren().add(currentLine);
                    lastX = x; lastY = y;
                }

                default -> selection.clearSelection();
            }
        });

        canvas.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (currentMode != Mode.PEN || currentLine == null) return;
            long now = System.currentTimeMillis();
            if (now - lastDrawTime < DELAY) return;
            double x = e.getX(), y = e.getY();
            if (Math.hypot(x - lastX, y - lastY) > DISTANCE) {
                currentLine.addPoint(x, y);
                lastX = x; lastY = y;
                lastDrawTime = now;
            }
        });

        canvas.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (currentMode != Mode.PEN || currentLine == null) return;
            if (currentLine.pointCount() >= 2) {
                selection.addSilently(currentLine);
            } else {
                canvasGroup.getChildren().remove(currentLine);
            }
            currentLine = null;
        });
    }

    @FXML private void handleUploadButton() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Выберите фотографию");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg"));
        File file = fc.showOpenDialog(null);
        if (file == null) return;
        try {
            Image img = new Image(new FileInputStream(file));
            double w = Math.min(img.getWidth(), 300);
            double h = img.getWidth() > 0 ? w * img.getHeight() / img.getWidth() : 200;
            selection.add(new imageElement(img, 100, 100, w, h));
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
        }
    }

    private void setupZoomAndPan() {
        canvasGroup.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.MIDDLE) {
                isPanning = true;
                panStartX = e.getSceneX(); panStartY = e.getSceneY();
                translateStartX = translateX; translateStartY = translateY;
                canvasGroup.setCursor(Cursor.CLOSED_HAND);
                e.consume();
            }
        });
        canvasGroup.setOnMouseDragged(e -> {
            if (isPanning) {
                translateX = translateStartX + (e.getSceneX() - panStartX);
                translateY = translateStartY + (e.getSceneY() - panStartY);
                updateTransform();
                e.consume();
            }
        });
        canvasGroup.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.MIDDLE) {
                isPanning = false;
                canvasGroup.setCursor(Cursor.DEFAULT);
                e.consume();
            }
        });
    }

    private void setupSceneZoom() {
        pane.sceneProperty().addListener((obs, old, newScene) -> {
            if (newScene != null) {
                newScene.setOnScroll(e -> {
                    double delta = e.getDeltaY() > 0 ? 1.1 : 0.9;
                    zoomFactor = Math.max(0.5, Math.min(5.0, zoomFactor * delta));
                    updateTransform();
                    e.consume();
                });
            }
        });
    }

    private void setupCentering() {
        pane.sceneProperty().addListener((obs, old, newScene) -> {
            if (newScene != null) {
                newScene.widthProperty().addListener((w, o, n) -> centerCanvas());
                newScene.heightProperty().addListener((h, o, n) -> centerCanvas());
                centerCanvas();
            }
        });
    }

    private void centerCanvas() {
        if (pane.getScene() == null) return;
        double sw = pane.getScene().getWidth(), sh = pane.getScene().getHeight();
        translateX = (sw - canvas.getWidth()  * zoomFactor) / 2;
        translateY = (sh - canvas.getHeight() * zoomFactor) / 2 - 30;
        updateTransform();
    }

    private void updateTransform() {
        canvasGroup.setScaleX(zoomFactor);
        canvasGroup.setScaleY(zoomFactor);
        canvasGroup.setTranslateX(translateX);
        canvasGroup.setTranslateY(translateY);
    }

    private String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }
}