package org.example.canvasproject;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.CacheHint;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.canvasproject.collab.RemoteCursorOverlay;
import org.example.canvasproject.collab.SessionManager;
import org.example.canvasproject.collab.SyncManager;
import org.example.canvasproject.ui.InviteCodeDialog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class controller {

    @FXML private Canvas      canvas;
    @FXML private Button      penBtn, textBtn, figureBtn, imgBtm, noteBtn;
    @FXML private AnchorPane  pane;
    @FXML private ImageView   imageView;
    @FXML private Button      menuBtn;
    @FXML private Rectangle   overlay;
    @FXML private VBox        navPanel;
    @FXML private Label       navCanvasName;
    @FXML private Label       navCanvasSize;

    private GraphicsContext gc;

    private enum Mode { NONE, PEN, TEXT, FIGURE, NOTE }
    private Mode currentMode = Mode.NONE;

    private final Group canvasGroup = new Group();
    private Canvas gridCanvas;
    private boolean showGrid     = false;
    private boolean darkBg       = false;

    private double zoomFactor = 1.0;
    private double translateX = 0, translateY = 0;
    private boolean isPanning = false;
    private double panStartX, panStartY, translateStartX, translateStartY;

    private double lastX, lastY;
    private long   lastDrawTime;
    private static final double DISTANCE = 4.0;
    private static final long   DELAY    = 16;

    private final CommandManager commandManager = new CommandManager();
    private BoardModel model;
    private SelectionManager selection;

    private lineElement currentLine = null;

    private Color  penColor    = Color.BLACK;
    private double penWidth    = 4.0;
    private double penOpacity  = 1.0;
    private double fontSize    = 14.0;
    private Color  fontColor   = Color.BLACK;
    private String figureType  = "rect";
    private Color  figureColor = Color.web("#007AFF");
    private Color  noteColor   = Color.web("#FFD60A");

    private ToolbarPanel toolbarPanel;

    private boolean isNavOpen = false;
    private Timeline slideTimeline;
    private Timeline fadeTimeline;

    private SyncManager syncManager;
    private RemoteCursorOverlay remoteCursors;

    private int canvasWidth  = 1920;
    private int canvasHeight = 1080;
    private String ownerId   = "";


    @FXML
    public void initialize() {
        gc = canvas.getGraphicsContext2D();

        gridCanvas = new Canvas(canvas.getWidth(), canvas.getHeight());
        gridCanvas.setMouseTransparent(true);
        canvasGroup.getChildren().addAll(gridCanvas, canvas);

        pane.getChildren().add(0, canvasGroup);

        model     = new BoardModel(canvasGroup);
        selection = new SelectionManager(canvasGroup, model, commandManager);

        fillBackground();
        setupToolbarPanel();
        setupZoomAndPan();
        setupCanvasInteractions();
        setupCentering();
        setupSceneZoom();
        setupHotkeys();
        setupNavPanel();

        imgBtm.setOnAction(e -> handleUploadButton());
        penBtn.setOnAction(e    -> toggleMode(Mode.PEN));
        textBtn.setOnAction(e   -> toggleMode(Mode.TEXT));
        figureBtn.setOnAction(e -> toggleMode(Mode.FIGURE));
        noteBtn.setOnAction(e   -> toggleMode(Mode.NOTE));

        for (Button btn : new Button[]{textBtn, penBtn, figureBtn, imgBtm, noteBtn}) {
            ScaleTransition in  = new ScaleTransition(Duration.millis(100), btn);
            in.setToX(1.2); in.setToY(1.2);
            ScaleTransition out = new ScaleTransition(Duration.millis(100), btn);
            out.setToX(1.0); out.setToY(1.0);
            btn.setOnMouseEntered(ev -> in.playFromStart());
            btn.setOnMouseExited(ev  -> out.playFromStart());
        }

        initCollaboration();
    }

    public void setCanvasSize(int w, int h) {
        canvasWidth  = w;
        canvasHeight = h;
        canvas.setWidth(w);
        canvas.setHeight(h);
        gridCanvas.setWidth(w);
        gridCanvas.setHeight(h);
        fillBackground();
        if (navCanvasSize != null) {
            navCanvasSize.setText(w + "×" + h + " px");
        }
    }

    public void setOwner(String ownerId) {
        this.ownerId = ownerId;
    }

    private void fillBackground() {
        Platform.runLater(() -> {
            gc.setFill(darkBg ? Color.web("#1a1a2e") : Color.WHITE);
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        });
        canvas.setCache(true);
        canvas.setCacheHint(CacheHint.SPEED);
    }


    private void initCollaboration() {
        SessionManager session = SessionManager.getInstance();
        syncManager   = new SyncManager(model, session);
        remoteCursors = new RemoteCursorOverlay(canvasGroup);
        syncManager.setCursorCallback(remoteCursors::update);

        syncManager.setOnElementAdded(selection::attachInteraction);

        selection.setOnElementDeleted(el -> {
            if (syncManager != null) syncManager.notifyDeleted(el);
        });

        selection.setOnElementChanged(el -> {
            if (syncManager != null) syncManager.notifyUpdated(el);
        });

        if (session.isConnected()) {
            session.getClient().setMessageHandler(msg -> {
                String type = msg.optString("type", "");
                switch (type) {
                    case "ELEMENT_ADDED", "ELEMENT_DELETED", "ELEMENT_UPDATED",
                         "CURSOR_MOVED"  -> syncManager.onServerMessage(msg);
                    case "USER_JOINED"   -> showToast(msg.optString("username") + " присоединился", false);
                    case "USER_LEFT"     -> remoteCursors.remove(msg.optString("userId"));
                    case "INVITE_CODE"   -> { /* обрабатывает InviteCodeDialog */ }
                    case "CANVAS_DELETED"-> onCanvasDeletedWhileViewing();
                }
            });
            syncManager.setSyncEnabled(true);
        }

        if (navCanvasName != null) navCanvasName.setText(session.getActiveCanvasName());
        if (navCanvasSize != null) navCanvasSize.setText(canvasWidth + "×" + canvasHeight + " px");
    }

    public void initFromServer(org.json.JSONArray elements) {
        if (syncManager != null && elements != null) {
            syncManager.loadCanvasElements(elements);
            syncManager.setSyncEnabled(true);
        }
    }

    private void onCanvasDeletedWhileViewing() {
        showToast("Канвас был удалён владельцем", true);
        Platform.runLater(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {}
            onGoHome();
        });
    }


    private void setupNavPanel() {
        AnchorPane.setLeftAnchor(navPanel, 0.0);
        AnchorPane.setTopAnchor(navPanel, 0.0);
        AnchorPane.setBottomAnchor(navPanel, 0.0);

        navPanel.setTranslateX(-navPanel.getPrefWidth());
        navPanel.setVisible(false);
        overlay.setVisible(false);
        overlay.setOpacity(0);

        pane.widthProperty().addListener((obs, old, nw) -> {
            overlay.setWidth(nw.doubleValue());
            if (!isNavOpen) navPanel.setTranslateX(-navPanel.getPrefWidth());
        });
        pane.heightProperty().addListener((obs, old, nh) -> {
            overlay.setHeight(nh.doubleValue());
            navPanel.setPrefHeight(nh.doubleValue());
        });
    }

    @FXML
    public void toggleNavPanel() {
        if (isNavOpen) closeNavPanel(); else openNavPanel();
    }

    private void openNavPanel() {
        if (isNavOpen) return;
        isNavOpen = true;

        if (toolbarPanel != null && toolbarPanel.isVisible()) {
            toolbarPanel.hide();
            currentMode = Mode.NONE;
            updateCursor();
        }

        canvas.setDisable(true);
        penBtn.setDisable(true); textBtn.setDisable(true);
        figureBtn.setDisable(true); imgBtm.setDisable(true); noteBtn.setDisable(true);

        overlay.setVisible(true);
        if (fadeTimeline != null) fadeTimeline.stop();
        fadeTimeline = new Timeline(
            new KeyFrame(Duration.ZERO,        new KeyValue(overlay.opacityProperty(), 0)),
            new KeyFrame(Duration.millis(200),  new KeyValue(overlay.opacityProperty(), 0.55))
        );
        fadeTimeline.play();

        navPanel.setVisible(true);
        navPanel.setTranslateX(-navPanel.getPrefWidth());
        if (slideTimeline != null) slideTimeline.stop();
        slideTimeline = new Timeline(
            new KeyFrame(Duration.ZERO,        new KeyValue(navPanel.translateXProperty(), -navPanel.getPrefWidth())),
            new KeyFrame(Duration.millis(250),  new KeyValue(navPanel.translateXProperty(), 0))
        );
        slideTimeline.play();

        overlay.setOnMouseClicked(e -> closeNavPanel());
    }

    private void closeNavPanel() {
        if (!isNavOpen) return;
        isNavOpen = false;

        canvas.setDisable(false);
        penBtn.setDisable(false); textBtn.setDisable(false);
        figureBtn.setDisable(false); imgBtm.setDisable(false); noteBtn.setDisable(false);

        if (fadeTimeline != null) fadeTimeline.stop();
        fadeTimeline = new Timeline(
            new KeyFrame(Duration.ZERO,        new KeyValue(overlay.opacityProperty(), overlay.getOpacity())),
            new KeyFrame(Duration.millis(200),  new KeyValue(overlay.opacityProperty(), 0))
        );
        fadeTimeline.setOnFinished(e -> overlay.setVisible(false));
        fadeTimeline.play();

        if (slideTimeline != null) slideTimeline.stop();
        slideTimeline = new Timeline(
            new KeyFrame(Duration.ZERO,        new KeyValue(navPanel.translateXProperty(), navPanel.getTranslateX())),
            new KeyFrame(Duration.millis(250),  new KeyValue(navPanel.translateXProperty(), -navPanel.getPrefWidth()))
        );
        slideTimeline.setOnFinished(e -> navPanel.setVisible(false));
        slideTimeline.play();
    }


    @FXML
    private void onGoHome() {
        closeNavPanel();
        SessionManager session = SessionManager.getInstance();
        if (session.isConnected() && session.getActiveCanvasId() != null) {
            session.getClient().leaveCanvas(session.getActiveCanvasId());
        }
        session.setActiveCanvas(null, null);
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/canvas-list.fxml"));
            AnchorPane root   = loader.load();
            Stage stage       = (Stage) pane.getScene().getWindow();
            stage.setScene(new Scene(root, 960, 640));
            stage.setTitle("Мои канвасы");
            stage.setResizable(true);
            stage.setMinWidth(700);
            stage.setMinHeight(480);
        } catch (Exception e) {
            showToast("Ошибка перехода на главную: " + e.getMessage(), true);
        }
    }

    @FXML
    private void onInvite() {
        closeNavPanel();
        InviteCodeDialog.show(pane.getScene().getWindow());
    }

    @FXML
    private void onResizeCanvas() {
        closeNavPanel();
        javafx.scene.control.Dialog<int[]> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Изменить размер канваса");
        dialog.setHeaderText("Текущий размер: " + canvasWidth + "×" + canvasHeight);
        dialog.getDialogPane().getButtonTypes().addAll(
            javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(20));

        javafx.scene.control.TextField wf = new javafx.scene.control.TextField(String.valueOf(canvasWidth));
        javafx.scene.control.TextField hf = new javafx.scene.control.TextField(String.valueOf(canvasHeight));
        grid.add(new javafx.scene.control.Label("Ширина (px):"), 0, 0); grid.add(wf, 1, 0);
        grid.add(new javafx.scene.control.Label("Высота (px):"),  0, 1); grid.add(hf, 1, 1);
        grid.add(new javafx.scene.control.Label("400–4000"), 0, 2, 2, 1);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn != javafx.scene.control.ButtonType.OK) return null;
            try {
                int w = Math.max(400, Math.min(4000, Integer.parseInt(wf.getText().trim())));
                int h = Math.max(300, Math.min(4000, Integer.parseInt(hf.getText().trim())));
                return new int[]{w, h};
            } catch (NumberFormatException e) { return null; }
        });

        dialog.showAndWait().ifPresent(size -> {
            setCanvasSize(size[0], size[1]);
            String canvasId = SessionManager.getInstance().getActiveCanvasId();
            if (canvasId != null) CanvasSizeCache.put(canvasId, size[0], size[1]);
            showToast("Размер изменён: " + size[0] + "×" + size[1], false);
        });
    }

    @FXML
    private void onNewDocument() {
        new ArrayList<>(model.elements()).forEach(el -> {
            model.remove(el);
            if (syncManager != null) syncManager.notifyDeleted(el);
        });
        selection.clearSelection();
        fillBackground();
        if (showGrid) drawGrid();
        closeNavPanel();
        showToast("Холст очищен", false);
    }

    @FXML
    private void onExportPng() {
        closeNavPanel();

        FileChooser fc = new FileChooser();
        fc.setTitle("Экспорт в PNG");
        fc.setInitialFileName("canvas-export.png");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        File file = fc.showSaveDialog(pane.getScene().getWindow());
        if (file == null) return;

        try {
            boolean gridVisible = gridCanvas.isVisible();
            gridCanvas.setVisible(false);
            selection.clearSelection();

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage snapshot = canvasGroup.snapshot(params, null);

            gridCanvas.setVisible(gridVisible);

            int w = (int) snapshot.getWidth();
            int h = (int) snapshot.getHeight();

            BufferedImage bImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader pr = snapshot.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bImage.setRGB(x, y, pr.getArgb(x, y));
                }
            }
            ImageIO.write(bImage, "PNG", file);
            showToast("Экспортировано: " + file.getName(), false);
        } catch (Exception ex) {
            showToast("Ошибка экспорта: " + ex.getMessage(), true);
        }
    }


    private void drawGrid() {
        GraphicsContext ggc = gridCanvas.getGraphicsContext2D();
        ggc.clearRect(0, 0, gridCanvas.getWidth(), gridCanvas.getHeight());
        ggc.setStroke(darkBg ? Color.web("#ffffff22") : Color.web("#00000018"));
        ggc.setLineWidth(1);

        double step = 40;
        for (double x = 0; x <= gridCanvas.getWidth(); x += step) {
            ggc.strokeLine(x, 0, x, gridCanvas.getHeight());
        }
        for (double y = 0; y <= gridCanvas.getHeight(); y += step) {
            ggc.strokeLine(0, y, gridCanvas.getWidth(), y);
        }

        ggc.setStroke(darkBg ? Color.web("#ffffff44") : Color.web("#00000030"));
        ggc.setLineWidth(1.5);
        for (double x = 0; x <= gridCanvas.getWidth(); x += step * 5) {
            ggc.strokeLine(x, 0, x, gridCanvas.getHeight());
        }
        for (double y = 0; y <= gridCanvas.getHeight(); y += step * 5) {
            ggc.strokeLine(0, y, gridCanvas.getWidth(), y);
        }
    }

    @FXML
    private void onToggleBg() {
        darkBg = !darkBg;
        fillBackground();
        if (showGrid) drawGrid();
        closeNavPanel();
    }

    @FXML
    private void onResetZoom() {
        zoomFactor = 1.0;
        centerCanvas();
        closeNavPanel();
        showToast("Зум сброшен", false);
    }

    @FXML
    private void onUndo() {
        if (commandManager.undo()) selection.clearSelection();
        closeNavPanel();
    }

    @FXML
    private void onRedo() {
        if (commandManager.redo()) selection.clearSelection();
        closeNavPanel();
    }

    @FXML
    private void onClearAll() {
        new ArrayList<>(model.elements()).forEach(el -> {
            model.remove(el);
            if (syncManager != null) syncManager.notifyDeleted(el);
        });
        selection.clearSelection();
        fillBackground();
        if (showGrid) drawGrid();
        closeNavPanel();
        showToast("Всё удалено", false);
    }

    private void setupToolbarPanel() {
        toolbarPanel = new ToolbarPanel();

        toolbarPanel.setOnColorChanged(c -> {
            if (isNavOpen) return;
            switch (currentMode) {
                case PEN    -> penColor    = c;
                case FIGURE -> { figureColor = c; applyCurrentFigureColorToSelected(); }
                case NOTE   -> noteColor   = c;
            }
        });
        toolbarPanel.setOnLineWidthChanged(w  -> { if (!isNavOpen) penWidth = w; });
        toolbarPanel.setOnOpacityChanged(o    -> { if (!isNavOpen) penOpacity = o; });
        toolbarPanel.setOnFontSizeChanged(s   -> { if (!isNavOpen) { fontSize = s; applyCurrentTextStyleToSelected(); } });
        toolbarPanel.setOnFontColorChanged(c  -> { if (!isNavOpen) { fontColor = c; applyCurrentTextStyleToSelected(); } });
        toolbarPanel.setOnFigureSelected(f    -> { if (!isNavOpen) figureType = f; });

        pane.getChildren().add(toolbarPanel);
        toolbarPanel.widthProperty().addListener((o, ov, nv)  -> repositionToolbar());
        toolbarPanel.heightProperty().addListener((o, ov, nv) -> repositionToolbar());
        pane.widthProperty().addListener((o, ov, nv)  -> repositionToolbar());
        pane.heightProperty().addListener((o, ov, nv)  -> repositionToolbar());
    }

    private void applyCurrentTextStyleToSelected() {
        if (isNavOpen) return;
        Selectable sel = selection.getSelected();
        if (sel instanceof customTextField tf) {
            tf.setFontSize(fontSize);
            tf.setFontColor(fontColor);
        } else if (sel instanceof stickyNote sn) {
            sn.setFontSize(fontSize);
        }
    }

    private void applyCurrentFigureColorToSelected() {
        if (isNavOpen) return;
        Selectable sel = selection.getSelected();
        if (sel instanceof figureElement fig) {
            ElementMemento before = fig.save();
            fig.setFillColor(figureColor);
            ElementMemento after  = fig.save();
            commandManager.execute(new ChangeStateCommand(fig, before, after));
            if (syncManager != null) syncManager.notifyUpdated(fig);
            selection.refreshOverlay();
        }
    }

    private void repositionToolbar() {
        if (toolbarPanel == null) return;
        AnchorPane.clearConstraints(toolbarPanel);
        toolbarPanel.applyCss();
        toolbarPanel.layout();
        double panelW = toolbarPanel.prefWidth(-1);
        double panelH = toolbarPanel.prefHeight(-1);
        double x = (pane.getWidth() - panelW) / 2;
        double y = pane.getHeight() - panelH - 100;
        AnchorPane.setLeftAnchor(toolbarPanel, x);
        AnchorPane.setTopAnchor(toolbarPanel, y);
    }

    private void toggleMode(Mode mode) {
        if (isNavOpen) return;
        selection.clearSelection();
        if (currentMode == mode) {
            currentMode = Mode.NONE;
            toolbarPanel.hide();
            updateCursor();
            return;
        }
        currentMode = mode;
        updateCursor();
        ToolbarPanel.ToolMode toolMode = switch (mode) {
            case PEN    -> ToolbarPanel.ToolMode.PEN;
            case TEXT   -> ToolbarPanel.ToolMode.TEXT;
            case FIGURE -> ToolbarPanel.ToolMode.FIGURE;
            case NOTE   -> ToolbarPanel.ToolMode.NOTE;
            default     -> ToolbarPanel.ToolMode.NONE;
        };
        toolbarPanel.show(toolMode);
        Platform.runLater(this::repositionToolbar);
    }

    private void updateCursor() {
        if (isNavOpen) { canvas.setCursor(Cursor.DEFAULT); return; }
        canvas.setCursor(switch (currentMode) {
            case PEN    -> Cursor.CROSSHAIR;
            case TEXT   -> Cursor.TEXT;
            case FIGURE -> Cursor.CROSSHAIR;
            case NOTE   -> Cursor.HAND;
            default     -> Cursor.DEFAULT;
        });
    }

    private void setupHotkeys() {
        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            newScene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (isNavOpen) return;
                if (newScene.getFocusOwner() instanceof customTextField ctf && ctf.isEditMode()) return;
                if (newScene.getFocusOwner() instanceof stickyNote sn && sn.isEditMode()) return;
                if (newScene.getFocusOwner() instanceof javafx.scene.control.TextArea) return;
                if (newScene.getFocusOwner() instanceof javafx.scene.control.TextField tf && tf.isEditable()) return;

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
                } else if (e.isControlDown() && e.getCode() == KeyCode.E) {
                    onExportPng();
                    e.consume();
                }
            });
        });
    }

    private void setupCanvasInteractions() {
        canvas.setOnMousePressed(e -> {
            if (isNavOpen) return;
            double x = e.getX(), y = e.getY();
            if (e.getButton() != MouseButton.PRIMARY) return;

            switch (currentMode) {
                case TEXT -> {
                    customTextField tf = new customTextField(x, y);
                    tf.setFontSize(fontSize);
                    tf.setFontColor(fontColor);
                    selection.add(tf);
                    if (syncManager != null) syncManager.notifyAdded(tf);
                }
                case FIGURE -> {
                    figureElement fig = new figureElement(figureType, x - 60, y - 45, 120, 90, figureColor);
                    selection.add(fig);
                    if (syncManager != null) syncManager.notifyAdded(fig);
                }
                case NOTE -> {
                    stickyNote note = new stickyNote(x, y, noteColor);
                    note.setFontSize(fontSize);
                    selection.add(note);
                    if (syncManager != null) syncManager.notifyAdded(note);
                }
                case PEN -> {
                    selection.clearSelection();
                    currentLine = new lineElement(
                        Color.color(penColor.getRed(), penColor.getGreen(), penColor.getBlue(), penOpacity),
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
            if (isNavOpen || e.getButton() != MouseButton.PRIMARY) return;
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
            if (isNavOpen || e.getButton() != MouseButton.PRIMARY) return;
            if (currentMode != Mode.PEN || currentLine == null) return;
            if (currentLine.pointCount() >= 2) {
                selection.addSilently(currentLine);
                if (syncManager != null) syncManager.notifyAdded(currentLine);
            } else {
                canvasGroup.getChildren().remove(currentLine);
            }
            currentLine = null;
        });

        canvasGroup.setOnMouseMoved(e -> {
            if (syncManager != null && !isNavOpen) {
                syncManager.notifyCursorMove(e.getX(), e.getY());
            }
        });
    }

    @FXML
    private void handleUploadButton() {
        if (isNavOpen) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Выберите изображение");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp")
        );
        File file = fc.showOpenDialog(null);
        if (file == null) return;
        try {

            Image img = new Image(file.toURI().toString(), true);
            double w = Math.min(img.getWidth() > 0 ? img.getWidth() : 400, 400);
            double h = img.getWidth() > 0 ? w * img.getHeight() / img.getWidth() : 200;
            imageElement imgEl = new imageElement(img, 100, 100, w, h);
            selection.add(imgEl);
            if (syncManager != null) syncManager.notifyAdded(imgEl);
        } catch (Exception ex) {
            showToast("Ошибка загрузки изображения: " + ex.getMessage(), true);
        }
    }


    private void setupZoomAndPan() {
        canvasGroup.setOnMousePressed(e -> {
            if (isNavOpen) return;
            if (e.getButton() == MouseButton.MIDDLE) {
                isPanning = true;
                panStartX = e.getSceneX(); panStartY = e.getSceneY();
                translateStartX = translateX; translateStartY = translateY;
                canvasGroup.setCursor(Cursor.CLOSED_HAND);
                e.consume();
            }
        });
        canvasGroup.setOnMouseDragged(e -> {
            if (isNavOpen || !isPanning) return;
            translateX = translateStartX + (e.getSceneX() - panStartX);
            translateY = translateStartY + (e.getSceneY() - panStartY);
            updateTransform();
            e.consume();
        });
        canvasGroup.setOnMouseReleased(e -> {
            if (isNavOpen) return;
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
                    if (isNavOpen) return;
                    double delta = e.getDeltaY() > 0 ? 1.1 : 0.9;
                    zoomFactor = Math.max(0.1, Math.min(8.0, zoomFactor * delta));
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


    private void showToast(String text, boolean isError) {
        Label label = new Label(text);
        String color = isError ? "#ff3b30" : "#32d74b";
        label.setStyle(
            "-fx-background-color: #1e1e2e;" +
            "-fx-text-fill: " + color + ";" +
            "-fx-background-radius: 10;" +
            "-fx-padding: 8 16;" +
            "-fx-font-size: 13px;" +
            "-fx-border-color: " + color + "30;" +
            "-fx-border-radius: 10;" +
            "-fx-border-width: 1;"
        );
        label.setLayoutX(20);
        label.setLayoutY(70);
        pane.getChildren().add(label);

        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
            javafx.util.Duration.seconds(3)
        );
        delay.setOnFinished(e -> pane.getChildren().remove(label));
        delay.play();
    }

    private void showUserJoinedNotification(String username) {
        showToast(username + " присоединился", false);
    }

    private String toHex(Color c) {
        return String.format("#%02X%02X%02X",
            (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }
}
