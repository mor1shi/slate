package org.example.canvasproject.ui;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.canvasproject.CanvasSizeCache;
import org.example.canvasproject.collab.AppSettings;
import org.example.canvasproject.collab.CollaborationClient;
import org.example.canvasproject.collab.DeviceIdentity;
import org.example.canvasproject.collab.SessionManager;
import org.example.canvasproject.i18n.I18n;
import org.example.canvasproject.storage.LocalBoard;
import org.example.canvasproject.storage.LocalBoardService;
import org.kordamp.ikonli.javafx.FontIcon;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Список досок. Экран открывается сразу после запуска: устройство
 * аутентифицируется своим ключом, регистрация не нужна.
 */
public class CanvasListController {

    private static final Logger log = Logger.getLogger(CanvasListController.class.getName());

    @FXML private Label      deviceLabel;
    @FXML private Label      countLabel;
    @FXML private Label      statusLabel;
    @FXML private Button     createBtn;
    @FXML private Button     joinByCodeBtn;
    @FXML private Button     serverBtn;
    @FXML private Button     openBtn;
    @FXML private Button     renameBtn;
    @FXML private Button     deleteBtn;
    @FXML private Button     deviceBtn;
    @FXML private Button     profileBtn;
    @FXML private Button     quitBtn;
    @FXML private Button     emptyCreateBtn;
    @FXML private TextField  searchField;
    @FXML private FlowPane   cardsPane;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox       emptyState;

    private static final DateTimeFormatter TIME_FMT  = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("d MMM", new Locale("ru"));

    private final SessionManager session = SessionManager.getInstance();
    private final List<CanvasItem> items = new ArrayList<>();
    private final List<LocalBoard> localBoards = new ArrayList<>();
    private JSONArray remoteCanvases = new JSONArray();

    private String selectedId = null;
    private int pendingWidth  = 1920;
    private int pendingHeight = 1080;

    private boolean connecting = false;
    private boolean switchingIdentity = false;
    private Timeline reconnectTimeline;
    private PauseTransition statusHide;

    @FXML
    public void initialize() {
        deviceLabel.setText(DeviceIdentity.getInstance().getName());
        configureIcons();
        bindActions();
        setupSearch();
        setupShortcuts();
        updateSelectionActions();
        refreshLocalBoards();

        if (session.isConnected() && session.getClient() != null) {
            authenticate();
        } else {
            connect();
        }

        java.util.function.Consumer<org.json.JSONObject> listener = this::handleMessage;
        session.addMessageListener(listener);
        cardsPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) session.removeMessageListener(listener);
        });
    }


    /* ------------------------------------------------------------------ */
    /* Подключение и идентичность                                          */
    /* ------------------------------------------------------------------ */

    private String serverUrl() {
        return org.example.canvasproject.collab.AppSettings.serverUrl();
    }

    private void connect() {
        if (connecting) return;
        connecting = true;
        setStatus("Подключение к серверу…", false);
        try {
            CollaborationClient client = new CollaborationClient(serverUrl());
            client.setMessageHandler(session::dispatch);
            client.setOnConnected(() -> {
                connecting = false;
                authenticate();
            });
            client.setOnDisconnected(reason -> {
                connecting = false;
                session.fireConnectionLost();
                if (switchingIdentity) return;
                setStatus("Нет связи с сервером. Повторяю подключение…", true);
                scheduleReconnect();
            });
            session.setClient(client);
            client.connect();
        } catch (Exception e) {
            connecting = false;
            setStatus("Не удалось подключиться: " + e.getMessage(), true);
            scheduleReconnect();
        }
    }

    private void authenticate() {
        DeviceIdentity identity = DeviceIdentity.getInstance();
        deviceLabel.setText(identity.getName());
        session.getClient().authDevice(identity.getKey(), identity.getName());
    }

    private void reconnectWithNewIdentity() {
        switchingIdentity = true;
        stopReconnect();
        CollaborationClient old = session.getClient();
        session.onLogout();
        if (old != null && old.isOpen()) old.close();
        switchingIdentity = false;
        connecting = false;
        connect();
    }

    private void scheduleReconnect() {
        if (reconnectTimeline != null && reconnectTimeline.getStatus() == Timeline.Status.RUNNING) return;
        reconnectTimeline = new Timeline(new KeyFrame(Duration.seconds(4), e -> {
            if (!session.isConnected()) connect();
        }));
        reconnectTimeline.setCycleCount(Timeline.INDEFINITE);
        reconnectTimeline.play();
    }

    private void stopReconnect() {
        if (reconnectTimeline != null) {
            reconnectTimeline.stop();
            reconnectTimeline = null;
        }
    }


    /* ------------------------------------------------------------------ */
    /* Сообщения сервера                                                   */
    /* ------------------------------------------------------------------ */

    private void handleMessage(JSONObject msg) {
        String type = msg.optString("type");
        switch (type) {
            case "AUTH_OK" -> {
                connecting = false;
                stopReconnect();
                switchingIdentity = false;
                session.onLoginSuccess(
                        msg.optString("token"),
                        msg.optString("userId"),
                        msg.optString("username"),
                        msg.optString("color", ""),
                        msg.optString("avatar", ""));
                String name = msg.optString("username", "");
                if (!name.isBlank()) deviceLabel.setText(name);
                clearStatus();
                session.getClient().listCanvases();
            }
            case "CANVAS_LIST" -> populateCards(msg.optJSONArray("canvases"));
            case "CANVAS_CREATED" -> {
                JSONObject canvas = msg.optJSONObject("canvas");
                if (canvas != null) {
                    showInfo("Доска «" + canvas.optString("name") + "» создана");
                }
                session.getClient().listCanvases();
            }
            case "CANVAS_RENAMED" -> {
                showInfo("Название изменено");
                session.getClient().listCanvases();
            }
            case "CANVAS_DELETED" -> {
                showInfo("Доска удалена");
                session.getClient().listCanvases();
            }
            case "JOINED_CANVAS" -> {
                // Пока открыта доска, её сообщения обрабатывает controller (реконнект).
                if (session.getActiveCanvasId() == null) openCanvas(msg);
            }
            case "ERROR" -> {
                String message = msg.optString("message", "Неизвестная ошибка");
                if (message.toLowerCase().contains("authenticated")) {
                    authenticate();
                } else {
                    setStatus(message, true);
                }
            }
            default -> { }
        }
    }


    /* ------------------------------------------------------------------ */
    /* Действия                                                            */
    /* ------------------------------------------------------------------ */

    private void bindActions() {
        createBtn.setOnAction(e -> createCanvas());
        emptyCreateBtn.setOnAction(e -> createCanvas());
        joinByCodeBtn.setOnAction(e -> joinByCode());
        serverBtn.setOnAction(e -> openServerDialog());
        openBtn.setOnAction(e -> openSelected());
        renameBtn.setOnAction(e -> renameSelected());
        deleteBtn.setOnAction(e -> deleteSelected());
        profileBtn.setOnAction(e -> ProfileDialog.show(getWindow(), () -> {
            if (session.getUsername() != null) deviceLabel.setText(session.getUsername());
        }));
        deviceBtn.setOnAction(e -> DeviceKeyDialog.show(getWindow(), this::reconnectWithNewIdentity));
        quitBtn.setOnAction(e -> Platform.exit());
    }

    private void setupSearch() {
        searchField.textProperty().addListener((o, oldV, newV) -> renderCards());
    }

    private void setupShortcuts() {
        cardsPane.sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene == null) return;
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), this::createCanvas);
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), searchField::requestFocus);
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.F2), this::renameSelected);
        });
    }

    private void createCanvas() {
        Dialog<JSONObject> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("dialog.create.title"));
        dialog.setHeaderText(I18n.t("dialog.create.header"));
        dialog.initOwner(getWindow());
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/style-auth.css").toExternalForm());
        ButtonType createType = new ButtonType(
                I18n.t("button.create"),
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(
                I18n.t("button.cancel"),
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, cancelType);

        TextField nameField   = new TextField("Моя доска");
        TextField widthField  = new TextField("1920");
        TextField heightField = new TextField("1080");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(18, 4, 4, 4));

        grid.add(new Label(I18n.t("dialog.create.name")), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label(I18n.t("dialog.create.width")), 0, 1);
        grid.add(widthField, 1, 1);
        grid.add(new Label(I18n.t("dialog.create.height")), 0, 2);
        grid.add(heightField, 1, 2);

        Label hint = new Label(I18n.t("dialog.create.range"));
        hint.getStyleClass().add("hint-text");
        grid.add(hint, 1, 3);

        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setVisible(false);
        error.setManaged(false);
        error.setWrapText(true);
        grid.add(error, 1, 4);

        GridPane.setHgrow(nameField, Priority.ALWAYS);
        nameField.setPrefWidth(240);

        dialog.getDialogPane().setContent(grid);
        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(createType);
        okButton.getStyleClass().add("primary-btn");

        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            error.setVisible(false);
            error.setManaged(false);
            String name = nameField.getText().trim();
            int w, h;
            try {
                w = Integer.parseInt(widthField.getText().trim());
                h = Integer.parseInt(heightField.getText().trim());
            } catch (NumberFormatException ex) {
                error.setText(I18n.t("dialog.create.numberInvalid"));
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
                return;
            }
            if (name.isEmpty()) {
                error.setText(I18n.t("dialog.create.nameRequired"));
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
                return;
            }
            if (w < 400 || h < 300 || w > 4000 || h > 4000) {
                error.setText(I18n.t("dialog.create.sizeInvalid"));
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn != createType) return null;
            JSONObject r = new JSONObject();
            r.put("name", nameField.getText().trim());
            r.put("width", Integer.parseInt(widthField.getText().trim()));
            r.put("height", Integer.parseInt(heightField.getText().trim()));
            return r;
        });

        dialog.showAndWait().ifPresent(data -> {
            pendingWidth  = data.optInt("width", 1920);
            pendingHeight = data.optInt("height", 1080);
            if (session.isConnected() && session.getClient() != null) {
                session.getClient().createCanvas(data.optString("name"), pendingWidth, pendingHeight);
            } else {
                LocalBoardService.getInstance().create(
                        data.optString("name"), pendingWidth, pendingHeight);
                refreshLocalBoards();
                showInfo("Доска создана локально");
            }
        });
    }

    private void openServerDialog() {
        textInputDialog(
                I18n.t("dialog.server.title"),
                I18n.t("dialog.server.header"),
                I18n.t("dialog.server.prompt"),
                AppSettings.serverUrl(),
                I18n.t("button.save"))
                .ifPresent(value -> {
                    String url = value.trim();
                    if (!url.matches("^wss?://.+")) {
                        setStatus(I18n.t("dialog.server.invalid"), true);
                        return;
                    }
                    if (url.equals(AppSettings.serverUrl())) return;
                    AppSettings.setServerUrl(url);
                    reconnectToServer();
                });
    }

    /** Диалог одной строки с локализованными кнопками и общим стилем. */
    private java.util.Optional<String> textInputDialog(String title, String header,
                                                       String prompt, String initial,
                                                       String okLabel) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(getWindow());
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/style-auth.css").toExternalForm());
        dialog.getDialogPane().setGraphic(null);

        ButtonType okType = new ButtonType(okLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(
                I18n.t("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);

        TextField field = new TextField(initial);
        field.setPrefWidth(320);
        Label label = new Label(prompt);
        VBox box = new VBox(8, label, field);
        box.setPadding(new Insets(16, 4, 4, 4));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().lookupButton(okType).getStyleClass().add("primary-btn");
        dialog.setResultConverter(btn -> btn == okType ? field.getText() : null);
        Platform.runLater(field::requestFocus);
        return dialog.showAndWait();
    }

    /** Переподключение после смены адреса сервера. */
    private void reconnectToServer() {
        stopReconnect();
        connecting = false;
        switchingIdentity = true;
        if (session.getClient() != null) {
            try {
                session.getClient().close();
            } catch (Exception ignored) {
            }
        }
        session.onLogout();
        PauseTransition pause = new PauseTransition(Duration.millis(500));
        pause.setOnFinished(e -> {
            switchingIdentity = false;
            connect();
        });
        pause.play();
    }

    private void joinByCode() {
        textInputDialog(
                I18n.t("dialog.join.title"),
                I18n.t("dialog.join.header"),
                I18n.t("dialog.join.prompt"),
                "",
                I18n.t("button.open"))
                .ifPresent(code -> {
                    if (!code.isBlank()) {
                        session.getClient().joinByCode(code.trim().toUpperCase());
                    }
                });
    }

    private void openSelected() {
        if (selectedId == null) return;
        CanvasItem item = findSelected();
        if (item == null) return;
        pendingWidth  = item.width;
        pendingHeight = item.height;

        if (item.local) {
            LocalBoardService.getInstance().find(item.id).ifPresentOrElse(
                    this::openLocalBoard,
                    () -> setStatus("Локальная доска не найдена", true));
        } else {
            session.getClient().joinCanvas(selectedId);
        }
    }

    /** Открывает доску из SQLite: сеть не используется, все правки идут в файл. */
    private void openLocalBoard(LocalBoard board) {
        try {
            var controller = showCanvasScene(board.getName());
            if (controller == null) return;
            controller.initLocalBoard(board);
        } catch (Exception e) {
            log.log(Level.WARNING, "Cannot open local board " + board.getId(), e);
            setStatus("Ошибка открытия доски: " + e.getMessage(), true);
        }
    }

    private org.example.canvasproject.controller showCanvasScene(String canvasName) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/canvas-view.fxml"));
        AnchorPane root = loader.load();
        var stage = (javafx.stage.Stage) cardsPane.getScene().getWindow();
        double w = stage.getWidth(), h = stage.getHeight();
        stage.setScene(new Scene(root, w, h));
        stage.setTitle("Slate — " + canvasName);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        Object ctrl = loader.getController();
        return ctrl instanceof org.example.canvasproject.controller controller ? controller : null;
    }

    private void renameSelected() {
        CanvasItem item = findSelected();
        if (item == null) return;

        textInputDialog(
                I18n.t("dialog.rename.title"),
                I18n.t("dialog.rename.header"),
                I18n.t("dialog.create.name"),
                item.name,
                I18n.t("button.rename"))
                .ifPresent(name -> {
                    if (name.isBlank()) return;
                    if (item.local) {
                        LocalBoardService.getInstance().rename(item.id, name.trim());
                        refreshLocalBoards();
                        showInfo("Название изменено");
                    } else {
                        session.getClient().renameCanvas(item.id, name.trim());
                    }
                });
    }

    private void deleteSelected() {
        CanvasItem item = findSelected();
        if (item == null) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(getWindow());
        alert.setTitle(I18n.t("dialog.delete.title"));
        alert.setHeaderText(I18n.t("dialog.delete.header", item.name));
        alert.setContentText(I18n.t("dialog.delete.text"));
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/style-auth.css").toExternalForm());
        alert.getDialogPane().setGraphic(null);
        ButtonType deleteType = new ButtonType(
                I18n.t("button.delete"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(
                I18n.t("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(deleteType, cancelType);
        alert.showAndWait().ifPresent(btn -> {
            if (btn != deleteType) return;
            if (item.local) {
                LocalBoardService.getInstance().delete(item.id);
                refreshLocalBoards();
                showInfo("Доска удалена");
            } else {
                session.getClient().deleteCanvas(item.id);
            }
        });
    }

    private CanvasItem findSelected() {
        return items.stream().filter(i -> i.id.equals(selectedId)).findFirst().orElse(null);
    }


    /* ------------------------------------------------------------------ */
    /* Карточки                                                           */
    /* ------------------------------------------------------------------ */

    private void populateCards(JSONArray arr) {
        remoteCanvases = arr == null ? new JSONArray() : arr;
        rebuildItems();
    }

    /** Локальные доски и серверные живут в одном списке; локальные — сверху. */
    private void rebuildItems() {
        items.clear();
        selectedId = null;
        updateSelectionActions();

        for (LocalBoard board : localBoards) {
            CanvasSizeCache.put(board.getId(), board.getWidth(), board.getHeight());
            items.add(new CanvasItem(board.getId(), board.getName(),
                    board.getWidth(), board.getHeight(), board.getUpdatedAt(), true));
        }
        for (int i = 0; i < remoteCanvases.length(); i++) {
            JSONObject c = remoteCanvases.getJSONObject(i);
            String id = c.optString("id", "");
            if (id.isBlank()) continue;
            int w = c.optInt("width", c.optInt("canvasWidth", 1920));
            int h = c.optInt("height", c.optInt("canvasHeight", 1080));
            if (w <= 0) w = 1920;
            if (h <= 0) h = 1080;
            CanvasSizeCache.put(id, w, h);
            items.add(new CanvasItem(id, c.optString("name", "Без названия"),
                    w, h, c.optLong("updatedAt", 0), false));
        }
        items.sort(Comparator.comparing(CanvasItem::local).reversed()
                .thenComparing(Comparator.comparingLong(CanvasItem::updatedAt).reversed()));
        renderCards();
    }

    private void refreshLocalBoards() {
        localBoards.clear();
        try {
            localBoards.addAll(LocalBoardService.getInstance().list());
        } catch (Exception e) {
            log.log(Level.WARNING, "Cannot read local boards", e);
        }
        rebuildItems();
    }

    private void renderCards() {
        String query = searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<CanvasItem> visible = items.stream()
                .filter(i -> query.isEmpty() || i.name.toLowerCase(Locale.ROOT).contains(query))
                .toList();

        cardsPane.getChildren().clear();
        for (CanvasItem item : visible) {
            cardsPane.getChildren().add(buildCard(item));
        }

        updateCount(visible.size());
        showEmpty(visible.isEmpty(), !query.isEmpty());
    }

    private VBox buildCard(CanvasItem item) {
        VBox card = new VBox(8);
        card.setAlignment(Pos.TOP_LEFT);
        card.setPrefSize(204, 136);
        card.setMinSize(204, 136);
        card.setMaxSize(204, 136);
        card.setPadding(new Insets(14));
        card.setFocusTraversable(true);
        card.setAccessibleRole(AccessibleRole.BUTTON);
        card.setAccessibleText(item.name + ", " + item.width + " на " + item.height);
        card.setUserData(item.id);
        card.getStyleClass().add("canvas-card");

        Label name = new Label(item.name);
        name.getStyleClass().add("canvas-card-title");
        name.setWrapText(true);
        name.setMaxWidth(176);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label size = new Label(item.width + " × " + item.height);
        size.getStyleClass().add("canvas-card-meta");

        Label updated = new Label(formatUpdated(item.updatedAt));
        updated.getStyleClass().add("canvas-card-time");

        card.getChildren().addAll(name, spacer, size, updated);
        if (item.local) {
            Label badge = new Label("локально");
            badge.getStyleClass().addAll("canvas-card-time", "canvas-card-badge");
            card.getChildren().add(badge);
        }

        card.setOnMouseClicked(e -> {
            selectCard(item.id);
            if (e.getClickCount() == 2) openSelected();
        });
        card.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER, SPACE -> { selectCard(item.id); openSelected(); e.consume(); }
                case F2 -> { selectCard(item.id); renameSelected(); e.consume(); }
                case DELETE, BACK_SPACE -> { selectCard(item.id); deleteSelected(); e.consume(); }
                default -> { }
            }
        });

        MenuItem rename = new MenuItem("Переименовать");
        rename.setOnAction(e -> { selectCard(item.id); renameSelected(); });
        MenuItem delete = new MenuItem("Удалить");
        delete.setOnAction(e -> { selectCard(item.id); deleteSelected(); });
        ContextMenu menu = new ContextMenu(rename, delete);
        card.setOnContextMenuRequested(e -> menu.show(card, e.getScreenX(), e.getScreenY()));

        refreshCardStyles(card, item.id.equals(selectedId));
        return card;
    }

    private void selectCard(String id) {
        selectedId = id;
        for (var node : cardsPane.getChildren()) {
            if (node instanceof VBox card) {
                refreshCardStyles(card, id.equals(card.getUserData()));
            }
        }
        updateSelectionActions();
    }

    private void refreshCardStyles(VBox card, boolean selected) {
        card.getStyleClass().removeAll("canvas-card", "canvas-card-selected");
        card.getStyleClass().add(selected ? "canvas-card-selected" : "canvas-card");

        Label name = (Label) card.getChildren().get(0);
        name.getStyleClass().removeAll("canvas-card-title", "canvas-card-title-selected");
        name.getStyleClass().add(selected ? "canvas-card-title-selected" : "canvas-card-title");

        for (int i = 1; i < card.getChildren().size(); i++) {
            if (card.getChildren().get(i) instanceof Label meta) {
                meta.getStyleClass().removeAll("canvas-card-meta", "canvas-card-meta-selected");
                if (!meta.getStyleClass().contains("canvas-card-time")) {
                    meta.getStyleClass().add(selected ? "canvas-card-meta-selected" : "canvas-card-meta");
                }
            }
        }
    }

    private void updateSelectionActions() {
        boolean none = selectedId == null;
        openBtn.setDisable(none);
        renameBtn.setDisable(none);
        deleteBtn.setDisable(none);
    }

    private void updateCount(int visible) {
        int total = items.size();
        if (total == 0) {
            countLabel.setText("Досок пока нет");
        } else if (visible == total) {
            countLabel.setText(total + " " + plural(total, "доска", "доски", "досок"));
        } else {
            countLabel.setText("Найдено: " + visible + " из " + total);
        }
    }

    private void showEmpty(boolean show, boolean filtered) {
        emptyState.setVisible(show);
        emptyState.setManaged(show);
        scrollPane.setVisible(!show);
        if (show) {
            Label title = (Label) emptyState.getChildren().get(0);
            Label text  = (Label) emptyState.getChildren().get(1);
            if (filtered) {
                title.setText("Ничего не найдено");
                text.setText("Попробуйте другой запрос или очистите поиск.");
                emptyCreateBtn.setVisible(false);
            } else {
                title.setText("Пока пусто");
                text.setText("Создайте первую доску — она сохранится за этим устройством " +
                        "и откроется снова после перезапуска.");
                emptyCreateBtn.setVisible(true);
            }
        }
    }

    private String formatUpdated(long millis) {
        if (millis <= 0) return "без изменений";
        var zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        LocalDate date = zoned.toLocalDate();
        LocalDate today = LocalDate.now();
        if (date.isEqual(today)) return "сегодня, " + zoned.format(TIME_FMT);
        if (date.isEqual(today.minusDays(1))) return "вчера, " + zoned.format(TIME_FMT);
        return zoned.format(DATE_FMT);
    }

    private static String plural(int n, String one, String few, String many) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return one;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
        return many;
    }


    /* ------------------------------------------------------------------ */
    /* Открытие доски                                                      */
    /* ------------------------------------------------------------------ */

    private void openCanvas(JSONObject msg) {
        String canvasId   = msg.optString("canvasId");
        String canvasName = msg.optString("canvasName", "Доска");

        int width  = msg.optInt("canvasWidth", msg.optInt("width", pendingWidth));
        int height = msg.optInt("canvasHeight", msg.optInt("height", pendingHeight));

        session.setActiveCanvas(canvasId, canvasName, msg.optString("role", "editor"));

        try {
            log.info("Opening canvas " + canvasId + " (" + canvasName + ")");
            var controller = showCanvasScene(canvasName);
            if (controller != null) {
                controller.setCanvasSize(width, height);
                controller.initFromServer(msg.optJSONArray("elements"));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Cannot open canvas " + canvasId, e);
            session.setActiveCanvas(null, null);
            setStatus("Ошибка открытия доски: " + e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }


    /* ------------------------------------------------------------------ */
    /* Статус и мелочи                                                     */
    /* ------------------------------------------------------------------ */

    private void setStatus(String message, boolean isError) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().setAll("status-banner");
        if (isError) statusLabel.getStyleClass().add("status-banner-error");
        statusLabel.setManaged(true);
        statusLabel.setVisible(true);

        if (statusHide != null) statusHide.stop();
        if (!isError) {
            statusHide = new PauseTransition(Duration.seconds(4));
            statusHide.setOnFinished(e -> clearStatus());
            statusHide.play();
        }
    }

    private void showInfo(String message) {
        setStatus(message, false);
    }

    private void clearStatus() {
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);
    }

    private Window getWindow() {
        return cardsPane.getScene() != null ? cardsPane.getScene().getWindow() : null;
    }

    private void configureIcons() {
        setIcon(createBtn, "plus", "Новая доска");
        setIcon(joinByCodeBtn, "key", "Открыть по коду");
        setIcon(openBtn, "arrow-right", "Открыть доску");
        setIcon(renameBtn, "edit-2", "Переименовать доску");
        setIcon(deleteBtn, "trash-2", "Удалить доску");
        setIcon(profileBtn, "user", "Профиль");
        setIcon(deviceBtn, "shield", "Ключ устройства");
        setIcon(quitBtn, "log-out", "Закрыть приложение");
    }

    private void setIcon(Button button, String name, String label) {
        FontIcon icon = new FontIcon("fth-" + name);
        icon.setIconSize(16);
        if (button == createBtn) {
            icon.setIconColor(Color.web("#1A1A1A"));
        } else if (button == deleteBtn) {
            icon.setIconColor(Color.web("#EF4444"));
        } else {
            icon.setIconColor(Color.web("#E8E8E8"));
        }
        button.setGraphic(icon);
        button.setGraphicTextGap(9);
        button.setAccessibleText(label);
    }

    record CanvasItem(String id, String name, int width, int height,
                      long updatedAt, boolean local) { }
}
