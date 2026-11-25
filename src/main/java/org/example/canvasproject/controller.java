package org.example.canvasproject;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.CacheHint;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.example.canvasproject.collab.RemoteCursorOverlay;
import org.example.canvasproject.collab.SessionManager;
import org.example.canvasproject.collab.SyncManager;
import org.example.canvasproject.storage.LocalBoard;
import org.example.canvasproject.storage.LocalBoardService;
import org.example.canvasproject.ui.InviteCodeDialog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class controller {

    @FXML private Canvas      canvas;
    @FXML private Button      selectBtn, penBtn, textBtn, figureBtn, imgBtm, noteBtn;
    @FXML private AnchorPane  pane;
    @FXML private Button      menuBtn;
    @FXML private Rectangle   overlay;
    @FXML private VBox        navPanel;
    @FXML private Label       navCanvasName;
    @FXML private Label       navCanvasSize;
    @FXML private Button      homeBtn, inviteBtn, resizeBtn, exportBtn;
    @FXML private Button      backgroundBtn, gridBtn, zoomInBtn, zoomOutBtn, resetZoomBtn;    @FXML private Button      undoBtn, redoBtn, clearBtn;
    @FXML private Button      selectAllBtn;
    @FXML private Label       connectionLabel, selectionStatus;

    private GraphicsContext gc;

    private enum Mode { NONE, SELECT, PEN, TEXT, FIGURE, NOTE }
    private Mode currentMode = Mode.SELECT;

    private final Group canvasGroup = new Group();
    private final Scale canvasScale = new Scale(1, 1, 0, 0);
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
    private LocalBoard localBoard;
    private Timeline localAutosave;
    private final java.util.function.Consumer<org.json.JSONObject> serverMessageListener =
            this::handleServerMessage;

    private int canvasWidth  = 1920;
    private int canvasHeight = 1080;
    private String ownerId   = "";

    // --- Выделение рамкой и групповые операции ---
    private record ClipboardItem(String type, org.json.JSONObject data) {}
    private final List<ClipboardItem> clipboard = new ArrayList<>();

    private MarqueeOverlay marqueeOverlay;
    private boolean marqueePending;
    private boolean marqueeStarted;
    private double marqueeStartCanvasX, marqueeStartCanvasY;
    private double marqueeStartSceneX, marqueeStartSceneY;
    private double marqueeEndCanvasX, marqueeEndCanvasY;
    private List<Selectable> marqueeCandidates = List.of();

    private boolean selectionDragging;
    private double dragStartCanvasX, dragStartCanvasY;
    private double dragStartSceneX, dragStartSceneY;

    private Timeline autoScrollTimeline;
    private double lastPointerSceneX, lastPointerSceneY;


    @FXML
    public void initialize() {
        gc = canvas.getGraphicsContext2D();
        canvasGroup.getTransforms().add(canvasScale);

        gridCanvas = new Canvas(canvas.getWidth(), canvas.getHeight());
        gridCanvas.setMouseTransparent(true);
        canvasGroup.getChildren().addAll(canvas, gridCanvas);

        pane.getChildren().add(0, canvasGroup);

        model     = new BoardModel(canvasGroup);
        selection = new SelectionManager(canvasGroup, model, commandManager);
        selection.setGridSnap(showGrid);
        selection.setOnSelectionChanged(this::onSelectionCountChanged);
        commandManager.setHistoryListener(this::syncHistoryChange);

        fillBackground();
        updateWorkspaceBackground();
        setupToolbarPanel();
        setupZoomAndPan();
        setupCanvasInteractions();
        setupSelectionInteractions();
        setupAutoScroll();
        setupCentering();
        setupSceneZoom();
        setupHotkeys();
        setupNavPanel();

        configureToolButtons();
        configureNavigationIcons();
        imgBtm.setOnAction(e -> handleUploadButton());
        selectBtn.setOnAction(e -> toggleMode(Mode.SELECT));
        penBtn.setOnAction(e    -> toggleMode(Mode.PEN));
        textBtn.setOnAction(e   -> toggleMode(Mode.TEXT));
        figureBtn.setOnAction(e -> toggleMode(Mode.FIGURE));
        noteBtn.setOnAction(e   -> toggleMode(Mode.NOTE));
        if (selectAllBtn != null) {
            selectAllBtn.setText(org.example.canvasproject.i18n.I18n.t("menu.selectAll"));
            selectAllBtn.setOnAction(e -> { selectAll(); closeNavPanel(); });
        }
        updateToolButtons();
        updateSelectionStatus(0);

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
        centerCanvas();
        if (navCanvasSize != null) {
            navCanvasSize.setText(w + "×" + h + " px"
                    + (SessionManager.getInstance().isViewer() ? " · только просмотр" : ""));
        }
    }

    public void setOwner(String ownerId) {
        this.ownerId = ownerId;
    }

    private void fillBackground() {
        Platform.runLater(() -> {
            gc.setFill(darkBg ? Color.web("#1F1F1F") : Color.WHITE);
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
        syncManager.setAssetStore(LocalBoardService.getInstance().storage().assets());
        syncManager.setAssetRequest(assetId -> {
            SessionManager s = SessionManager.getInstance();
            if (s.getClient() != null && s.isConnected() && !s.isLocalMode()) {
                s.getClient().getAsset(assetId);
            }
        });

        syncManager.setOnElementAdded(el -> {
            selection.attachInteraction(el);
            syncManager.watchTextChanges(el);
        });
        syncManager.setOnElementRemoved(el -> selection.handleRemoteRemoved(el));
        syncManager.setOnElementChanged(el -> selection.handleRemoteChanged(el));

        selection.setOnElementDeleted(el -> {
            if (syncManager != null) syncManager.notifyDeleted(el);
        });
        selection.setOnElementsDeleted(els -> {
            if (syncManager != null) syncManager.notifyDeletedBatch(els);
        });
        selection.setOnElementsRestored(els -> {
            if (syncManager != null) {
                for (Selectable el : els) syncManager.notifyAdded(el);
            }
        });
        selection.setOnElementsChanged(els -> {
            if (syncManager != null) syncManager.notifyUpdatedBatch(els);
        });
        selection.setOnGestureProgress(els -> {
            if (syncManager != null) syncManager.notifyMoving(els);
        });

        selection.setOnElementChanged(el -> {
            if (syncManager != null) syncManager.notifyUpdated(el);
        });

        if (session.isConnected() && !session.isLocalMode()) {
            syncManager.setSyncEnabled(true);
        }

        session.addMessageListener(serverMessageListener);
        session.setConnectionLostListener(() -> updateConnection(false));
        updateConnection(session.isConnected());

        if (navCanvasName != null) navCanvasName.setText(session.getActiveCanvasName());
        if (navCanvasSize != null) navCanvasSize.setText(canvasWidth + "×" + canvasHeight + " px");
        applyRolePermissions(session);
    }

    /** Все сообщения доски идут через один диспетчер сессии; обработчик не подменяется. */
    private void handleServerMessage(org.json.JSONObject msg) {
        SessionManager session = SessionManager.getInstance();
        String type = msg.optString("type", "");
        switch (type) {
            case "CURSOR_MOVED" ->
                    syncManager.onServerMessage(msg);
            case "ASSET_DATA" -> syncManager.applyAsset(msg.optString("assetId"),
                    java.util.Base64.getDecoder().decode(msg.optString("data", "")));
            case "AUTH_OK" -> rejoinAfterReconnect();
            case "JOINED_CANVAS" -> {
                if (msg.optString("canvasId").equals(session.getActiveCanvasId())) {
                    resyncBoard(msg);
                }
            }
            case "USER_JOINED" -> showToast(msg.optString("username") + " в сети", false);
            case "USER_UPDATED" -> remoteCursors.updateProfile(
                    msg.optString("userId"),
                    msg.optString("username"),
                    msg.optString("color", ""));
            case "USER_LEFT" -> remoteCursors.remove(msg.optString("userId"));
            case "ERROR" -> showToast(msg.optString("message", "Ошибка сервера"), true);
            case "CANVAS_DELETED" -> onCanvasDeletedWhileViewing();
            case "CANVAS_RESIZED" -> onCanvasResized(msg);
            default -> { }
        }
    }

    private void onCanvasResized(org.json.JSONObject msg) {
        SessionManager session = SessionManager.getInstance();
        if (!msg.optString("canvasId").equals(session.getActiveCanvasId())) return;
        int w = msg.optInt("canvasWidth", canvasWidth);
        int h = msg.optInt("canvasHeight", canvasHeight);
        setCanvasSize(w, h);
        CanvasSizeCache.put(session.getActiveCanvasId(), w, h);
        showToast("Размер доски: " + w + "×" + h, false);
    }

    private void rejoinAfterReconnect() {
        SessionManager session = SessionManager.getInstance();
        if (session.isConnected() && session.getActiveCanvasId() != null) {
            session.getClient().joinCanvas(session.getActiveCanvasId());
        }
    }

    /** После реконнекта сервер присылает доску целиком: заменяем локальную копию. */
    private void resyncBoard(org.json.JSONObject msg) {
        int w = msg.optInt("canvasWidth", canvasWidth);
        int h = msg.optInt("canvasHeight", canvasHeight);
        setCanvasSize(w, h);

        if (selection != null) selection.clearSelection();
        for (Selectable el : new ArrayList<>(model.elements())) model.remove(el);

        initFromServer(msg.optJSONArray("elements"));
        updateConnection(true);
        showToast("Связь восстановлена", false);
    }

    private void updateConnection(boolean online) {
        if (connectionLabel == null) return;
        connectionLabel.setText(online ? "● В сети" : "● Нет связи");
        connectionLabel.getStyleClass().setAll("connection-badge",
                online ? "connection-online" : "connection-offline");
    }

    /** Наблюдатель видит доску, но не может её менять. Сервер это тоже проверяет. */
    private void applyRolePermissions(SessionManager session) {
        if (!session.isViewer()) return;
        Button[] editing = {penBtn, textBtn, figureBtn, imgBtm, noteBtn,
                undoBtn, redoBtn, clearBtn, inviteBtn};
        for (Button b : editing) {
            if (b != null) b.setDisable(true);
        }
        if (toolbarPanel != null) toolbarPanel.hide();
        selection.setEditable(false);
        Button[] extra = {resizeBtn, backgroundBtn, gridBtn};
        for (Button b : extra) {
            if (b != null) b.setDisable(true);
        }
        if (navCanvasSize != null) {
            navCanvasSize.setText(canvasWidth + "×" + canvasHeight + " px · только просмотр");
        }
        showToast("Доска открыта в режиме просмотра", false);
    }

    public void initFromServer(org.json.JSONArray elements) {
        if (syncManager != null && elements != null) {
            syncManager.loadCanvasElements(elements);
            syncManager.setSyncEnabled(true);
        }
    }

    /** Открывает доску из локального SQLite-хранилища: без сервера и сети. */
    public void initLocalBoard(LocalBoard board) {
        this.localBoard = board;
        SessionManager session = SessionManager.getInstance();
        session.setLocalMode(true);
        session.setActiveCanvas(board.getId(), board.getName(), "owner");
        wireLocalTransport(session);

        setCanvasSize(board.getWidth(), board.getHeight());
        syncManager.loadCanvasElements(board.getElements());
        if (inviteBtn != null) inviteBtn.setDisable(true);
        updateConnection(false);
        if (connectionLabel != null) {
            connectionLabel.setText("● Локально");
            connectionLabel.getStyleClass().setAll("connection-badge", "connection-online");
        }

        localAutosave = new Timeline(new KeyFrame(Duration.seconds(2), e -> saveLocalNow()));
        localAutosave.setCycleCount(Timeline.INDEFINITE);
        localAutosave.play();
    }

    /** Solo-режим идёт через тот же слой команд и проверки прав, что и сетевой сервер. */
    private void wireLocalTransport(SessionManager session) {
        try {
            if (session.getClient() == null) {
                session.setClient(new org.example.canvasproject.collab.CollaborationClient(
                        "ws://127.0.0.1:8765"));
            }
            var server = new org.example.canvasproject.transport.LocalServer(
                    LocalBoardService.getInstance().storage(),
                    org.example.canvasproject.storage.BoardRecord.LOCAL_OWNER,
                    "Локальный", "owner");
            session.getClient().setLocalTransport(
                    new org.example.canvasproject.transport.InProcessTransport(server));
            session.onLoginSuccess("local",
                    org.example.canvasproject.storage.BoardRecord.LOCAL_OWNER, "Локальный");
            if (syncManager != null) syncManager.setSyncEnabled(true);
        } catch (Exception e) {
            showToast("Локальный режим недоступен: " + e.getMessage(), true);
        }
    }

    private void saveLocalNow() {
        if (localBoard == null || syncManager == null) return;
        localBoard.setWidth(canvasWidth);
        localBoard.setHeight(canvasHeight);
        localBoard.setElements(syncManager.serializeAll());
        try {
            LocalBoardService.getInstance().save(localBoard);
        } catch (Exception e) {
            showToast("Не удалось сохранить доску: " + e.getMessage(), true);
        }
    }

    private void onCanvasDeletedWhileViewing() {
        showToast("Канвас был удалён владельцем", true);
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(Duration.seconds(2));
        delay.setOnFinished(e -> onGoHome());
        delay.play();
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
            new KeyFrame(Duration.millis(200),  new KeyValue(overlay.opacityProperty(), 0.35))
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
        session.removeMessageListener(serverMessageListener);
        session.setConnectionLostListener(null);
        if (localAutosave != null) {
            localAutosave.stop();
            localAutosave = null;
        }
        if (localBoard != null) saveLocalNow();
        session.setLocalMode(false);
        if (session.isConnected() && session.getActiveCanvasId() != null) {
            session.getClient().leaveCanvas(session.getActiveCanvasId());
        }
        session.setActiveCanvas(null, null);
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/canvas-list.fxml"));
            AnchorPane root   = loader.load();
            Stage stage       = (Stage) pane.getScene().getWindow();
            double w = stage.getWidth(), h = stage.getHeight();
            stage.setScene(new Scene(root, w, h));
            stage.setTitle("Slate — Мои доски");
            stage.setResizable(true);
            stage.setMinWidth(820);
            stage.setMinHeight(540);
        } catch (Exception e) {
            showToast("Ошибка перехода на главную: " + e.getMessage(), true);
        }
    }

    @FXML
    private void onInvite() {
        if (SessionManager.getInstance().isViewer()) {
            showToast("В режиме просмотра приглашать нельзя", true);
            return;
        }
        closeNavPanel();
        InviteCodeDialog.show(pane.getScene().getWindow());
    }

    @FXML
    private void onResizeCanvas() {
        closeNavPanel();
        SessionManager session = SessionManager.getInstance();
        if (session.isViewer()) {
            showToast(org.example.canvasproject.i18n.I18n.t("toast.viewerDenied"), true);
            return;
        }

        javafx.scene.control.Dialog<int[]> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(org.example.canvasproject.i18n.I18n.t("dialog.resize.title"));
        dialog.setHeaderText(org.example.canvasproject.i18n.I18n.t(
                "dialog.resize.header", canvasWidth, canvasHeight));
        dialog.setGraphic(null);
        dialog.getDialogPane().setMinWidth(400);
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/style-auth.css").toExternalForm());
        javafx.scene.control.ButtonType okType = new javafx.scene.control.ButtonType(
                org.example.canvasproject.i18n.I18n.t("button.save"),
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancelType = new javafx.scene.control.ButtonType(
                org.example.canvasproject.i18n.I18n.t("button.cancel"),
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.setPrefWidth(360);
        grid.setPadding(new javafx.geometry.Insets(20));

        javafx.scene.control.TextField wf = new javafx.scene.control.TextField(String.valueOf(canvasWidth));
        javafx.scene.control.TextField hf = new javafx.scene.control.TextField(String.valueOf(canvasHeight));
        wf.setPrefWidth(200);
        hf.setPrefWidth(200);
        grid.add(new javafx.scene.control.Label(
                org.example.canvasproject.i18n.I18n.t("dialog.resize.width")), 0, 0); grid.add(wf, 1, 0);
        grid.add(new javafx.scene.control.Label(
                org.example.canvasproject.i18n.I18n.t("dialog.resize.height")), 0, 1); grid.add(hf, 1, 1);
        grid.add(new javafx.scene.control.Label(
                org.example.canvasproject.i18n.I18n.t("dialog.resize.range")), 0, 2, 2, 1);
        javafx.scene.control.Label error = new javafx.scene.control.Label();
        error.getStyleClass().add("error-label");
        error.setVisible(false);
        error.setManaged(false);
        grid.add(error, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(grid);

        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(okType);
        okButton.getStyleClass().add("primary-btn");
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            error.setVisible(false);
            error.setManaged(false);
            try {
                int w = Integer.parseInt(wf.getText().trim());
                int h = Integer.parseInt(hf.getText().trim());
                if (w < 400 || w > 4000 || h < 300 || h > 4000) {
                    error.setText(org.example.canvasproject.i18n.I18n.t("dialog.resize.invalid"));
                    error.setVisible(true);
                    error.setManaged(true);
                    ev.consume();
                }
            } catch (NumberFormatException ex) {
                error.setText(org.example.canvasproject.i18n.I18n.t("dialog.create.numberInvalid"));
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn != okType) return null;
            return new int[]{
                    Integer.parseInt(wf.getText().trim()),
                    Integer.parseInt(hf.getText().trim())};
        });

        dialog.showAndWait().ifPresent(size -> {
            String canvasId = session.getActiveCanvasId();
            if (canvasId == null || session.getClient() == null) return;
            session.getClient().resizeCanvas(canvasId, size[0], size[1]);
        });
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

        boolean gridVisible = gridCanvas.isVisible();
        boolean cursorsVisible = remoteCursors != null;
        double previousZoom = zoomFactor;
        double previousTranslateX = translateX;
        double previousTranslateY = translateY;
        try {
            gridCanvas.setVisible(false);
            selection.setSelectionVisible(false);
            if (remoteCursors != null) remoteCursors.setVisible(false);
            zoomFactor = 1.0;
            translateX = 0;
            translateY = 0;
            updateTransform();

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage snapshot = canvasGroup.snapshot(params, null);

            int w = (int) snapshot.getWidth();
            int h = (int) snapshot.getHeight();

            BufferedImage bImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader pr = snapshot.getPixelReader();
            int[] pixels = new int[w * h];
            pr.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
            bImage.setRGB(0, 0, w, h, pixels, 0, w);
            ImageIO.write(bImage, "PNG", file);
            showToast("Экспортировано: " + file.getName(), false);
        } catch (Exception ex) {
            showToast("Ошибка экспорта: " + ex.getMessage(), true);
        } finally {
            gridCanvas.setVisible(gridVisible);
            selection.setSelectionVisible(true);
            if (remoteCursors != null) remoteCursors.setVisible(cursorsVisible);
            zoomFactor = previousZoom;
            translateX = previousTranslateX;
            translateY = previousTranslateY;
            updateTransform();
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
        updateWorkspaceBackground();
        if (showGrid) drawGrid();
        closeNavPanel();
    }

    @FXML
    private void onToggleGrid() {
        showGrid = !showGrid;
        if (selection != null) selection.setGridSnap(showGrid);
        if (showGrid) {
            drawGrid();
        } else {
            gridCanvas.getGraphicsContext2D().clearRect(
                    0, 0, gridCanvas.getWidth(), gridCanvas.getHeight());
        }
        if (gridBtn != null) {
            gridBtn.setText(org.example.canvasproject.i18n.I18n.t(
                    showGrid ? "nav.grid.hide" : "nav.grid.show"));
        }        closeNavPanel();
    }

    @FXML
    private void onZoomIn() {
        zoomAt(pane.getWidth() / 2, pane.getHeight() / 2, 1.15);
        closeNavPanel();
    }

    @FXML
    private void onZoomOut() {
        zoomAt(pane.getWidth() / 2, pane.getHeight() / 2, 1 / 1.15);
        closeNavPanel();
    }

    @FXML
    private void onResetZoom() {
        fitToWindow();
        closeNavPanel();
        showToast("Доска вписана в окно", false);
    }

    @FXML
    private void onUndo() {
        if (SessionManager.getInstance().isViewer()) return;
        if (commandManager.undo()) selection.clearSelection();
        closeNavPanel();
    }

    /** Undo/Redo должны менять доску на сервере, а не только локально. */
    private void syncHistoryChange(command cmd, boolean redo) {
        if (syncManager == null) return;
        if (cmd instanceof AddCommand add) {
            if (redo) syncManager.notifyAdded(add.getElement());
            else      syncManager.notifyDeleted(add.getElement());
        } else if (cmd instanceof DeleteCommand del) {
            if (redo) syncManager.notifyDeleted(del.getElement());
            else      syncManager.notifyAdded(del.getElement());
        } else if (cmd instanceof ChangeStateCommand change) {
            syncManager.notifyUpdated(change.getElement());
        } else if (cmd instanceof GroupChangeCommand group) {
            syncManager.notifyUpdatedBatch(group.getElements());
        } else if (cmd instanceof DeleteElementsCommand del) {
            if (redo) syncManager.notifyDeletedBatch(del.getElements());
            else for (Selectable el : del.getElements()) syncManager.notifyAdded(el);
        } else if (cmd instanceof AddElementsCommand add) {
            if (redo) for (Selectable el : add.getElements()) syncManager.notifyAdded(el);
            else syncManager.notifyDeletedBatch(add.getElements());
        }
    }

    @FXML
    private void onRedo() {
        if (SessionManager.getInstance().isViewer()) return;
        if (commandManager.redo()) selection.clearSelection();
        closeNavPanel();
    }

    @FXML
    private void onClearAll() {
        SessionManager session = SessionManager.getInstance();
        if (session.isViewer()) {
            showToast("В режиме просмотра нельзя изменять доску", true);
            return;
        }
        if (model.elements().isEmpty()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(canvas.getScene().getWindow());
        confirm.setTitle("Очистить доску");
        confirm.setHeaderText("Удалить все элементы?");
        confirm.setContentText("Действие можно отменить кнопкой Undo или Ctrl+Z.");
        confirm.getButtonTypes().setAll(
                new ButtonType("Очистить", ButtonBar.ButtonData.OK_DONE),
                new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE));
        var answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) return;

        ClearBoardCommand clear = new ClearBoardCommand(model,
                el -> { if (syncManager != null) syncManager.notifyDeleted(el); },
                el -> { if (syncManager != null) syncManager.notifyAdded(el); });
        commandManager.execute(clear);

        selection.clearSelection();
        fillBackground();
        if (showGrid) drawGrid();
        closeNavPanel();
        showToast("Доска очищена. Ctrl+Z — отменить", false);
    }

    private void setupToolbarPanel() {
        toolbarPanel = new ToolbarPanel();

        toolbarPanel.setOnColorChanged(c -> {
            if (isNavOpen) return;
            if (currentMode == Mode.SELECT) { applySelectionColor(c); return; }
            switch (currentMode) {
                case PEN    -> penColor    = c;
                case FIGURE -> { figureColor = c; applyCurrentFigureColorToSelected(); }
                case NOTE   -> noteColor   = c;
            }
        });
        toolbarPanel.setOnLineWidthChanged(w  -> {
            if (isNavOpen) return;
            if (currentMode == Mode.SELECT) { selection.applyLineWidth(w); return; }
            penWidth = w;
        });
        toolbarPanel.setOnOpacityChanged(o    -> {
            if (isNavOpen) return;
            if (currentMode == Mode.SELECT) { selection.applyOpacity(o); return; }
            penOpacity = o;
        });
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

    private void applySelectionColor(Color color) {
        if (!selection.isEditable() || selection.selectionCount() == 0) return;
        selection.applyColor(color);
    }

    private void toggleMode(Mode mode) {
        if (isNavOpen) return;
        if (mode == Mode.SELECT) {
            currentMode = Mode.SELECT;
            updateCursor();
            updateToolButtons();
            showSelectionToolbar();
            return;
        }
        selection.clearSelection();
        if (currentMode == mode) {
            currentMode = Mode.NONE;
            toolbarPanel.hide();
            updateCursor();
            updateToolButtons();
            return;
        }
        currentMode = mode;
        updateCursor();
        updateToolButtons();
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

    private void updateToolButtons() {
        Button[] tools = {selectBtn, penBtn, textBtn, figureBtn, noteBtn};
        for (Button b : tools) {
            if (b != null) b.getStyleClass().remove("tool-active");
        }
        Button active = switch (currentMode) {
            case SELECT -> selectBtn;
            case PEN -> penBtn;
            case TEXT -> textBtn;
            case FIGURE -> figureBtn;
            case NOTE -> noteBtn;
            default -> null;
        };
        if (active != null && !active.getStyleClass().contains("tool-active")) {
            active.getStyleClass().add("tool-active");
        }
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

    private void setupHotkeys() {
        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            newScene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (isNavOpen) {
                    if (e.getCode() == KeyCode.ESCAPE) {
                        closeNavPanel();
                        e.consume();
                    }
                    return;
                }
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
                } else if (e.isControlDown() && (e.getCode() == KeyCode.PLUS
                        || e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.ADD)) {
                    zoomAt(pane.getWidth() / 2, pane.getHeight() / 2, 1.15);
                    e.consume();
                } else if (e.isControlDown() && (e.getCode() == KeyCode.MINUS
                        || e.getCode() == KeyCode.SUBTRACT)) {
                    zoomAt(pane.getWidth() / 2, pane.getHeight() / 2, 1 / 1.15);
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.DIGIT0) {
                    onResetZoom();
                    e.consume();
                } else if (currentMode == Mode.SELECT
                        && selection.selectionCount() > 0
                        && isArrowKey(e.getCode())) {
                    if (!SessionManager.getInstance().isViewer()) {
                        double step = e.isShiftDown() ? 10 : 1;
                        switch (e.getCode()) {
                            case LEFT  -> nudgeSelection(-step, 0);
                            case RIGHT -> nudgeSelection(step, 0);
                            case UP    -> nudgeSelection(0, -step);
                            case DOWN  -> nudgeSelection(0, step);
                            default -> { }
                        }
                    }
                    e.consume();
                } else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                    if (SessionManager.getInstance().isViewer()) return;
                    selection.deleteSelected();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.A) {
                    selectAll();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.C) {
                    copySelection();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.X) {
                    cutSelection();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.V) {
                    pasteClipboard();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.D) {
                    duplicateSelection();
                    e.consume();
                } else if (e.getCode() == KeyCode.TAB && !e.isControlDown()) {
                    selectNext(e.isShiftDown());
                    e.consume();
                } else if (e.getCode() == KeyCode.V && !e.isControlDown()) {
                    toggleMode(Mode.SELECT);
                    e.consume();
                } else if (e.getCode() == KeyCode.ESCAPE) {
                    if (selection.selectionCount() > 0) {
                        selection.clearSelection();
                    } else if (currentMode != Mode.SELECT) {
                        currentMode = Mode.SELECT;
                        toolbarPanel.hide();
                        updateCursor();
                        updateToolButtons();
                    }
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.G) {
                    onToggleGrid();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.E) {
                    onExportPng();
                    e.consume();
                }
            });
        });
    }

    private void setupSelectionInteractions() {
        canvasGroup.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onSelectionPressed);
        canvasGroup.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onSelectionDragged);
        canvasGroup.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onSelectionReleased);
    }

    private boolean isSelectionControlTarget(Object target) {
        if (!(target instanceof javafx.scene.Node node)) return false;
        while (node != null) {
            if (node instanceof SelectionOverlay
                    || node instanceof GroupSelectionOverlay
                    || node instanceof MarqueeOverlay) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void onSelectionPressed(MouseEvent e) {
        if (isNavOpen || currentMode != Mode.SELECT) return;
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (isSelectionControlTarget(e.getTarget())) return;

        Point2D p = SelectionGeometry.toCanvas(canvasGroup, e.getSceneX(), e.getSceneY());
        Selectable hit = model.findAt(p.getX(), p.getY());

        if (e.getClickCount() >= 2 && hit != null) {
            // Двойной клик оставляем тексту и стикеру: они сами входят в режим правки.
            return;
        }

        marqueeStartSceneX = e.getSceneX();
        marqueeStartSceneY = e.getSceneY();
        lastPointerSceneX = e.getSceneX();
        lastPointerSceneY = e.getSceneY();

        if (hit != null) {
            if (e.isShiftDown() || e.isControlDown()) {
                selection.toggleSelection(List.of(hit));
            } else if (!selection.isSelected(hit)) {
                selection.setSelection(List.of(hit));
            }
            if (selection.isSelected(hit) && selection.isEditable()) {
                selectionDragging = true;
                dragStartCanvasX = p.getX();
                dragStartCanvasY = p.getY();
                dragStartSceneX = e.getSceneX();
                dragStartSceneY = e.getSceneY();
                selection.beginGesture();
            }
            showSelectionToolbar();
            e.consume();
            return;
        }

        // Пустое место: рамка начнётся только после порога протягивания.
        marqueePending = true;
        marqueeStarted = false;
        marqueeStartCanvasX = p.getX();
        marqueeStartCanvasY = p.getY();
        marqueeEndCanvasX = p.getX();
        marqueeEndCanvasY = p.getY();
        marqueeCandidates = List.of();
        e.consume();
    }

    private void onSelectionDragged(MouseEvent e) {
        if (isNavOpen || currentMode != Mode.SELECT) return;
        if (!e.isPrimaryButtonDown()) return;

        if (selectionDragging) {
            Point2D start = SelectionGeometry.toCanvas(canvasGroup, dragStartSceneX, dragStartSceneY);
            Point2D current = SelectionGeometry.toCanvas(canvasGroup, e.getSceneX(), e.getSceneY());
            double dx = current.getX() - start.getX();
            double dy = current.getY() - start.getY();
            Bounds groupStart = selection.getGestureStartBounds();
            if (showGrid && groupStart != null) {
                double step = selection.getGridSize();
                dx = Math.round((groupStart.getMinX() + dx) / step) * step - groupStart.getMinX();
                dy = Math.round((groupStart.getMinY() + dy) / step) * step - groupStart.getMinY();
            }
            selection.moveGesture(dx, dy);
            e.consume();
            return;
        }

        if (!marqueePending) return;
        double sceneDistance = Math.hypot(e.getSceneX() - marqueeStartSceneX,
                e.getSceneY() - marqueeStartSceneY);
        if (!marqueeStarted && sceneDistance < SelectionGeometry.DRAG_THRESHOLD) {
            e.consume();
            return;
        }
        if (!marqueeStarted) {
            marqueeStarted = true;
            marqueeOverlay = new MarqueeOverlay();
            canvasGroup.getChildren().add(marqueeOverlay);
            marqueeOverlay.toFront();
        }
        Point2D p = SelectionGeometry.toCanvas(canvasGroup, e.getSceneX(), e.getSceneY());
        marqueeEndCanvasX = p.getX();
        marqueeEndCanvasY = p.getY();
        lastPointerSceneX = e.getSceneX();
        lastPointerSceneY = e.getSceneY();
        refreshMarquee();
        updateAutoScroll(e.getSceneX(), e.getSceneY());
        e.consume();
    }

    private void onSelectionReleased(MouseEvent e) {
        if (isNavOpen || currentMode != Mode.SELECT) return;
        if (e.getButton() != MouseButton.PRIMARY) return;

        stopAutoScroll();

        if (selectionDragging) {
            selectionDragging = false;
            GroupChangeCommand command = selection.endGesture();
            if (command != null && syncManager != null) {
                syncManager.notifyMoveFinished(command.getElements());
            }
            showSelectionToolbar();
            e.consume();
            return;
        }

        if (!marqueePending) return;
        boolean started = marqueeStarted;
        List<Selectable> candidates = marqueeCandidates;
        marqueePending = false;
        marqueeStarted = false;
        marqueeCandidates = List.of();
        removeMarquee();

        if (started) {
            if (e.isShiftDown() || e.isControlDown()) {
                selection.toggleSelection(candidates);
            } else {
                selection.setSelection(candidates);
            }
        } else if (!e.isShiftDown() && !e.isControlDown()) {
            // Обычный клик по пустому месту снимает выделение.
            selection.clearSelection();
        }
        showSelectionToolbar();
        e.consume();
    }

    private void refreshMarquee() {
        if (marqueeOverlay == null) return;
        Bounds frame = SelectionGeometry.normalize(
                marqueeStartCanvasX, marqueeStartCanvasY, marqueeEndCanvasX, marqueeEndCanvasY);
        boolean containMode = SelectionGeometry.isContainMode(marqueeStartCanvasX, marqueeEndCanvasX);
        marqueeOverlay.setFrame(frame, containMode);
        marqueeCandidates = SelectionGeometry.select(model, frame, containMode);
        marqueeOverlay.setHighlights(marqueeCandidates);
    }

    private void removeMarquee() {
        if (marqueeOverlay != null) {
            canvasGroup.getChildren().remove(marqueeOverlay);
            marqueeOverlay = null;
        }
    }

    private void setupAutoScroll() {
        autoScrollTimeline = new Timeline(new KeyFrame(Duration.millis(30), e -> tickAutoScroll()));
        autoScrollTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    private void updateAutoScroll(double sceneX, double sceneY) {
        lastPointerSceneX = sceneX;
        lastPointerSceneY = sceneY;
        double margin = 28;
        Point2D paneScene = pane.localToScene(0, 0);
        boolean nearEdge = sceneX < paneScene.getX() + margin
                || sceneX > paneScene.getX() + pane.getWidth() - margin
                || sceneY < paneScene.getY() + margin
                || sceneY > paneScene.getY() + pane.getHeight() - margin;
        if (!nearEdge) {
            stopAutoScroll();
        } else if (autoScrollTimeline != null && autoScrollTimeline.getStatus() != Timeline.Status.RUNNING) {
            autoScrollTimeline.play();
        }
    }

    private void tickAutoScroll() {
        if (!marqueeStarted) {
            stopAutoScroll();
            return;
        }
        double margin = 28;
        double step = 14;
        Point2D paneScene = pane.localToScene(0, 0);
        if (lastPointerSceneX < paneScene.getX() + margin) translateX += step;
        else if (lastPointerSceneX > paneScene.getX() + pane.getWidth() - margin) translateX -= step;
        if (lastPointerSceneY < paneScene.getY() + margin) translateY += step;
        else if (lastPointerSceneY > paneScene.getY() + pane.getHeight() - margin) translateY -= step;
        updateTransform();

        Point2D p = SelectionGeometry.toCanvas(canvasGroup, lastPointerSceneX, lastPointerSceneY);
        marqueeEndCanvasX = p.getX();
        marqueeEndCanvasY = p.getY();
        refreshMarquee();
    }

    private void stopAutoScroll() {
        if (autoScrollTimeline != null) autoScrollTimeline.stop();
    }

    private void selectAll() {
        if (model.elements().isEmpty()) return;
        selection.selectAll();
        showSelectionToolbar();
    }

    private boolean isArrowKey(KeyCode code) {
        return code == KeyCode.LEFT || code == KeyCode.RIGHT
                || code == KeyCode.UP || code == KeyCode.DOWN;
    }

    /** Сдвиг выделения стрелками: одно нажатие — одна команда Undo. */
    private void nudgeSelection(double dx, double dy) {
        if (!selection.isEditable() || selection.selectionCount() == 0) return;
        if (!selection.beginGesture()) return;
        Bounds start = selection.getGestureStartBounds();
        double stepX = dx;
        double stepY = dy;
        if (showGrid && start != null) {
            double step = selection.getGridSize();
            stepX = Math.round((start.getMinX() + dx) / step) * step - start.getMinX();
            stepY = Math.round((start.getMinY() + dy) / step) * step - start.getMinY();
        }
        selection.moveGesture(stepX, stepY);
        GroupChangeCommand command = selection.endGesture();
        if (command != null && syncManager != null) {
            syncManager.notifyMoveFinished(command.getElements());
        }
        showSelectionToolbar();
    }

    private void selectNext(boolean backwards) {        List<Selectable> elements = model.elements();
        if (elements.isEmpty()) return;
        int index = selection.getSelected() == null ? -1 : elements.indexOf(selection.getSelected());
        int next;
        if (index < 0) next = backwards ? elements.size() - 1 : 0;
        else next = Math.floorMod(index + (backwards ? -1 : 1), elements.size());
        selection.setSelection(List.of(elements.get(next)));
        showSelectionToolbar();
    }

    private void updateSelectionStatus(int count) {
        if (selectionStatus != null) {
            selectionStatus.setText(org.example.canvasproject.i18n.I18n.t("selection.count", count));
            selectionStatus.setVisible(count > 0);
            selectionStatus.setManaged(count > 0);
        }
        if (currentMode == Mode.SELECT) showSelectionToolbar();
    }

    private void showSelectionToolbar() {
        if (toolbarPanel == null) return;
        if (currentMode != Mode.SELECT || selection.selectionCount() == 0) {
            if (toolbarPanel.getActiveMode() == ToolbarPanel.ToolMode.SELECT) toolbarPanel.hide();
            return;
        }
        if (toolbarPanel.getActiveMode() != ToolbarPanel.ToolMode.SELECT) {
            toolbarPanel.show(ToolbarPanel.ToolMode.SELECT);
        }
        toolbarPanel.setDisable(!selection.isEditable());
        toolbarPanel.updateSelectionSummary(
                selection.selectionCount(),
                selection.hasMixedColor(),
                selection.hasMixedLineWidth(),
                selection.hasMixedOpacity(),
                selection.hasAnyColor(),
                selection.hasAnyLine());
        Platform.runLater(this::repositionToolbar);
    }

    private void onSelectionCountChanged(int count) {
        updateSelectionStatus(count);
    }

    // ---------------------------------------------------------------
    // Буфер обмена и групповые команды
    // ---------------------------------------------------------------

    private void copySelection() {
        if (syncManager == null) return;
        clipboard.clear();
        for (Selectable el : selection.getSelection()) {
            clipboard.add(new ClipboardItem(el.getType(), syncManager.serializeElement(el)));
        }
    }

    private void cutSelection() {
        if (!selection.isEditable() || selection.selectionCount() == 0 || syncManager == null) return;
        copySelection();
        selection.deleteSelected();
    }

    private void pasteClipboard() {
        if (clipboard.isEmpty() || !selection.isEditable() || syncManager == null) return;
        pasteItems(new ArrayList<>(clipboard), 24);
    }

    private void duplicateSelection() {
        if (!selection.isEditable() || selection.selectionCount() == 0 || syncManager == null) return;
        List<ClipboardItem> items = new ArrayList<>();
        for (Selectable el : selection.getSelection()) {
            items.add(new ClipboardItem(el.getType(), syncManager.serializeElement(el)));
        }
        pasteItems(items, 24);
    }

    private void pasteItems(List<ClipboardItem> items, double offset) {
        if (syncManager == null) return;
        List<Selectable> created = new ArrayList<>();
        for (ClipboardItem item : items) {
            Selectable copy = syncManager.createCopy(item.type(), item.data());
            if (copy == null) continue;
            copy.move(offset, offset);
            created.add(copy);
        }
        if (created.isEmpty()) return;

        AddElementsCommand command = new AddElementsCommand(model, created,
                added -> {
                    for (Selectable el : added) {
                        selection.attachInteraction(el);
                        if (syncManager != null) {
                            syncManager.watchTextChanges(el);
                            syncManager.notifyAdded(el);
                        }
                    }
                },
                removed -> {
                    if (syncManager != null) syncManager.notifyDeletedBatch(removed);
                });
        commandManager.execute(command);
        selection.setSelection(created);
        showSelectionToolbar();
    }

    private void setupCanvasInteractions() {
        canvas.setOnMousePressed(e -> {
            if (isNavOpen) return;
            if (SessionManager.getInstance().isViewer()) return;
            double x = e.getX(), y = e.getY();
            if (e.getButton() != MouseButton.PRIMARY) return;

            switch (currentMode) {
                case TEXT -> {
                    customTextField tf = new customTextField(x, y);
                    tf.setFontSize(fontSize);
                    tf.setFontColor(fontColor);
                    selection.add(tf);
                    if (syncManager != null) {
                        syncManager.notifyAdded(tf);
                        syncManager.watchTextChanges(tf);
                    }
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
                    if (syncManager != null) {
                        syncManager.notifyAdded(note);
                        syncManager.watchTextChanges(note);
                    }
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
            if (SessionManager.getInstance().isViewer()) return;
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
            } else if (currentLine.pointCount() == 1) {
                currentLine.ensureDot();
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
        File file = fc.showOpenDialog(pane.getScene().getWindow());
        if (file == null) return;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
            org.example.canvasproject.storage.ImageAssets.Prepared prepared =
                    org.example.canvasproject.storage.ImageAssets.prepare(raw);
            org.example.canvasproject.storage.AssetStore assets =
                    LocalBoardService.getInstance().storage().assets();
            String assetId = assets.put(prepared.bytes());

            Image img = new Image(new java.io.ByteArrayInputStream(prepared.bytes()));
            double ratio = Math.min(1, Math.min(400 / img.getWidth(), 300 / img.getHeight()));
            imageElement imgEl = new imageElement(
                img, 100, 100, img.getWidth() * ratio, img.getHeight() * ratio);
            imgEl.setAssetId(assetId);
            imgEl.setAssetMime(prepared.mimeType());
            selection.add(imgEl);
            if (syncManager != null) syncManager.notifyAdded(imgEl);

            SessionManager session = SessionManager.getInstance();
            if (session.getClient() != null && session.isConnected() && !session.isLocalMode()) {
                session.getClient().putAsset(assetId, prepared.mimeType(), prepared.bytes());
            }
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
        canvasGroup.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (isNavOpen) return;
            double deltaY = getScrollDeltaY(e);
            if (Math.abs(deltaY) < 0.0001) return;
            javafx.geometry.Point2D pointer = pane.sceneToLocal(e.getSceneX(), e.getSceneY());
            double exponent = Math.max(-0.45, Math.min(0.45, deltaY * 0.0015));
            zoomAt(pointer.getX(), pointer.getY(), Math.exp(exponent));
            e.consume();
        });
    }

    private boolean initialFitDone = false;

    private void setupCentering() {
        pane.sceneProperty().addListener((obs, old, newScene) -> {
            if (newScene != null) {
                newScene.widthProperty().addListener((w, o, n) -> centerCanvas());
                newScene.heightProperty().addListener((h, o, n) -> centerCanvas());
                javafx.application.Platform.runLater(() -> {
                    fitToWindow();
                    initialFitDone = true;
                });
            }
        });
    }

    /** Вписывает лист в окно с полями, чтобы рабочая область была видна целиком. */
    private void fitToWindow() {
        if (pane.getWidth() <= 0 || pane.getHeight() <= 0) return;
        double pad = 56;
        double scaleX = (pane.getWidth()  - pad * 2) / canvas.getWidth();
        double scaleY = (pane.getHeight() - pad * 2) / canvas.getHeight();
        zoomFactor = Math.max(0.1, Math.min(1.0, Math.min(scaleX, scaleY)));
        centerCanvas();
    }

    private void centerCanvas() {
        if (pane.getScene() == null) return;
        double width = pane.getWidth(), height = pane.getHeight();
        translateX = (width - canvas.getWidth() * zoomFactor) / 2;
        translateY = (height - canvas.getHeight() * zoomFactor) / 2;
        updateTransform();
    }

    private void updateTransform() {
        canvasScale.setX(zoomFactor);
        canvasScale.setY(zoomFactor);
        canvasGroup.setTranslateX(translateX);
        canvasGroup.setTranslateY(translateY);
    }

    private void updateWorkspaceBackground() {
        pane.getStyleClass().removeAll("canvas-surround-dark", "canvas-surround-light");
        pane.getStyleClass().add(darkBg ? "canvas-surround-light" : "canvas-surround-dark");
    }

    private double getScrollDeltaY(ScrollEvent event) {
        double deltaY = event.getDeltaY();
        if (Math.abs(deltaY) < 0.0001) {
            deltaY = event.getTextDeltaY() * 40;
        }
        if (Math.abs(deltaY) < 0.0001) {
            deltaY = event.getTotalDeltaY();
        }
        return deltaY;
    }

    private void zoomAt(double x, double y, double factor) {
        double nextZoom = Math.max(0.1, Math.min(8.0, zoomFactor * factor));
        if (Math.abs(nextZoom - zoomFactor) < 0.0001) return;
        double canvasX = (x - translateX) / zoomFactor;
        double canvasY = (y - translateY) / zoomFactor;
        zoomFactor = nextZoom;
        translateX = x - canvasX * zoomFactor;
        translateY = y - canvasY * zoomFactor;
        updateTransform();
    }

    private void configureToolButtons() {
        if (selectBtn != null) configureIconButton(selectBtn, "mouse-pointer",
                org.example.canvasproject.i18n.I18n.t("tool.select"),
                org.example.canvasproject.i18n.I18n.t("tool.select.tip"));
        configureToolButton(penBtn, "pen-tool", "Рисование", "Инструмент рисования");
        configureToolButton(textBtn, "type", "Текст", "Добавить текст");
        configureToolButton(figureBtn, "layers", "Фигуры", "Добавить фигуру");
        configureToolButton(imgBtm, "image", "Изображение", "Добавить изображение");
        configureToolButton(noteBtn, "file-text", "Заметка", "Добавить заметку");
    }

    private void configureToolButton(Button button, String iconName, String label, String tooltip) {
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(tooltip));
    }

    private void configureNavigationIcons() {
        configureIconButton(menuBtn, "menu", "Меню", "Открыть меню");
        configureIconButton(homeBtn, "home", "На главную", "Вернуться к списку канвасов");
        configureIconButton(inviteBtn, "mail", "Код приглашения", "Показать код приглашения");
        configureIconButton(resizeBtn, "crop", "Изменить размер", "Изменить размер холста");
        configureIconButton(exportBtn, "download", "Экспорт PNG", "Экспортировать в PNG");
        configureIconButton(backgroundBtn, "moon", "Цвет холста", "Переключить цвет холста");
        configureIconButton(gridBtn, "grid", "Сетка", "Показать или скрыть сетку (Ctrl+G)");
        configureIconButton(zoomInBtn, "zoom-in", "Увеличить", "Увеличить масштаб");
        configureIconButton(zoomOutBtn, "zoom-out", "Уменьшить", "Уменьшить масштаб");
        configureIconButton(resetZoomBtn, "rotate-ccw", "Сбросить масштаб", "Сбросить масштаб");
        configureIconButton(undoBtn, "corner-up-left", "Отменить", "Отменить последнее действие");
        configureIconButton(redoBtn, "corner-up-right", "Повторить", "Повторить действие");
        configureIconButton(clearBtn, "trash-2", "Очистить всё", "Очистить холст");
    }

    private void configureIconButton(Button button, String iconName, String label, String tooltip) {
        FontIcon icon = new FontIcon("fth-" + iconName);
        icon.setIconSize(18);
        icon.setIconColor(Color.web("#E8E8E8"));
        button.setGraphic(icon);
        button.setGraphicTextGap(10);
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(tooltip));
    }

    private void showToast(String text, boolean isError) {
        Label label = new Label(text);
        String color = isError ? "#EF4444" : "#22C55E";
        label.setStyle(
            "-fx-background-color: #2A2A2A;" +
            "-fx-text-fill: " + color + ";" +
            "-fx-background-radius: 8;" +
            "-fx-padding: 8 14;" +
            "-fx-font-size: 13px;" +
            "-fx-border-color: #474747;" +
            "-fx-border-radius: 8;" +
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
