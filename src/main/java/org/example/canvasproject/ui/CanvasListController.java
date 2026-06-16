package org.example.canvasproject.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.canvasproject.CanvasSizeCache;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CanvasListController {

    @FXML private Label     welcomeLabel;
    @FXML private Label     statusLabel;
    @FXML private Button    createBtn;
    @FXML private Button    joinByCodeBtn;
    @FXML private Button    openBtn;
    @FXML private Button    renameBtn;
    @FXML private Button    deleteBtn;
    @FXML private Button    logoutBtn;
    @FXML private FlowPane  cardsPane;   // карточки канвасов
    @FXML private ScrollPane scrollPane;

    private final SessionManager session = SessionManager.getInstance();
    private final List<CanvasItem> items = new ArrayList<>();
    private String selectedId = null;

    // размеры если сервер не вернул
    private int pendingWidth  = 1920;
    private int pendingHeight = 1080;

    @FXML
    public void initialize() {
        welcomeLabel.setText("Привет, " + session.getUsername() + "!");
        statusLabel.setVisible(false);

        session.getClient().setMessageHandler(msg -> {
            String type = msg.optString("type");
            switch (type) {
                case "CANVAS_LIST"    -> Platform.runLater(() -> populateCards(msg.optJSONArray("canvases")));
                case "CANVAS_CREATED" -> Platform.runLater(() -> onCanvasCreated(msg.optJSONObject("canvas")));
                case "CANVAS_RENAMED", "CANVAS_DELETED" -> {
                    Platform.runLater(() -> {
                        if ("CANVAS_DELETED".equals(type)) showStatus("Канвас удалён", false);
                        session.getClient().listCanvases();
                    });
                }
                case "JOINED_CANVAS"  -> Platform.runLater(() -> openCanvas(msg));
                case "ERROR"          -> Platform.runLater(() -> showStatus("Ошибка: " + msg.optString("message"), true));
            }
        });

        createBtn.setOnAction(e     -> createCanvas());
        joinByCodeBtn.setOnAction(e -> joinByCode());
        openBtn.setOnAction(e       -> openSelected());
        renameBtn.setOnAction(e     -> renameSelected());
        deleteBtn.setOnAction(e     -> deleteSelected());
        logoutBtn.setOnAction(e     -> logout());

        session.getClient().listCanvases();
    }

    private void createCanvas() {
        Dialog<JSONObject> dialog = new Dialog<>();
        dialog.setTitle("Новый канвас");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(12);
        grid.setPadding(new Insets(20));

        TextField nameField   = new TextField("Мой канвас");
        TextField widthField  = new TextField("1920");
        TextField heightField = new TextField("1080");

        grid.add(new Label("Название:"),    0, 0); grid.add(nameField,   1, 0);
        grid.add(new Label("Ширина (px):"), 0, 1); grid.add(widthField,  1, 1);
        grid.add(new Label("Высота (px):"), 0, 2); grid.add(heightField, 1, 2);
        grid.add(new Label("400–4000 px"),  0, 3, 2, 1);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            try {
                int w = Integer.parseInt(widthField.getText().trim());
                int h = Integer.parseInt(heightField.getText().trim());
                if (w < 400 || h < 300 || w > 4000 || h > 4000) {
                    showStatus("Размер: 400–4000 ширина, 300–4000 высота", true);
                    return null;
                }
                JSONObject r = new JSONObject();
                r.put("name", nameField.getText().trim());
                r.put("width", w); r.put("height", h);
                return r;
            } catch (NumberFormatException e) {
                showStatus("Размер должен быть числом", true);
                return null;
            }
        });

        dialog.showAndWait().ifPresent(data -> {
            int w = data.optInt("width", 1920);
            int h = data.optInt("height", 1080);
            pendingWidth  = w;
            pendingHeight = h;
            session.getClient().createCanvas(data.optString("name"), w, h);
        });
    }

    private void joinByCode() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Войти по коду");
        dialog.setHeaderText("Введите код приглашения");
        dialog.setContentText("Код:");
        dialog.showAndWait().ifPresent(code -> {
            if (!code.isBlank()) session.getClient().joinByCode(code.trim().toUpperCase());
        });
    }

    private void openSelected() {
        if (selectedId == null) { showStatus("Выберите канвас", false); return; }
        CanvasItem item = items.stream().filter(i -> i.id.equals(selectedId)).findFirst().orElse(null);
        if (item != null) {
            pendingWidth  = item.width;
            pendingHeight = item.height;
        }
        session.getClient().joinCanvas(selectedId);
    }

    private void renameSelected() {
        if (selectedId == null) { showStatus("Выберите канвас", false); return; }
        CanvasItem item = items.stream().filter(i -> i.id.equals(selectedId)).findFirst().orElse(null);
        String curName = item != null ? item.name : "";
        TextInputDialog d = new TextInputDialog(curName);
        d.setTitle("Переименовать");
        d.setHeaderText("Новое название");
        d.showAndWait().ifPresent(n -> {
            if (!n.isBlank()) session.getClient().renameCanvas(selectedId, n.trim());
        });
    }

    private void deleteSelected() {
        if (selectedId == null) { showStatus("Выберите канвас", false); return; }
        CanvasItem item = items.stream().filter(i -> i.id.equals(selectedId)).findFirst().orElse(null);
        String name = item != null ? item.name : "";
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Удалить канвас");
        a.setHeaderText("Удалить «" + name + "»?");
        a.showAndWait().ifPresent(b -> { if (b == ButtonType.OK) session.getClient().deleteCanvas(selectedId); });
    }

    private void logout() {
        session.getClient().logout();
        session.onLogout();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/login.fxml"));
            AnchorPane root = loader.load();
            javafx.stage.Stage stage = (javafx.stage.Stage) logoutBtn.getScene().getWindow();
            stage.setScene(new Scene(root, 480, 440));
            stage.setTitle("Вход");
        } catch (Exception e) { e.printStackTrace(); }
    }


    private void populateCards(JSONArray arr) {
        items.clear();
        selectedId = null;
        cardsPane.getChildren().clear();
        if (arr == null || arr.length() == 0) {
            showStatus("У вас пока нет канвасов. Создайте первый!", false);
            return;
        }
        statusLabel.setVisible(false);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            int serverW = c.optInt("width",  0);
            int serverH = c.optInt("height", 0);
            String cid  = c.optString("id");

            if (serverW > 0 && serverH > 0 && (serverW != 1920 || serverH != 1080)) {
                CanvasSizeCache.put(cid, serverW, serverH);
            } else if (!CanvasSizeCache.has(cid)) {
                CanvasSizeCache.put(cid, serverW > 0 ? serverW : 1920, serverH > 0 ? serverH : 1080);
            }

            int[] cached = CanvasSizeCache.get(cid);
            CanvasItem item = new CanvasItem(
                cid,
                c.optString("name", "Без названия"),
                cached[0], cached[1],
                c.optLong("updatedAt", 0)
            );
            items.add(item);
            cardsPane.getChildren().add(buildCard(item));
        }
    }

    private void onCanvasCreated(JSONObject canvas) {
        if (canvas == null) return;
        String id = canvas.optString("id", "");
        CanvasSizeCache.put(id, pendingWidth, pendingHeight);
        showStatus("Канвас «" + canvas.optString("name") + "» создан!", false);
        session.getClient().listCanvases();
    }

    private void openCanvas(JSONObject msg) {
        String canvasId   = msg.optString("canvasId");
        String canvasName = msg.optString("canvasName", "Канвас");

        int width, height;
        if (CanvasSizeCache.has(canvasId)) {
            width  = CanvasSizeCache.getWidth(canvasId);
            height = CanvasSizeCache.getHeight(canvasId);
        } else {
            width  = msg.optInt("width",  pendingWidth);
            height = msg.optInt("height", pendingHeight);
            CanvasSizeCache.put(canvasId, width, height);
        }

        session.setActiveCanvas(canvasId, canvasName);

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/canvas-view.fxml"));
            AnchorPane root = loader.load();
            javafx.stage.Stage stage = (javafx.stage.Stage) createBtn.getScene().getWindow();
            stage.setScene(new Scene(root, 1200, 800));
            stage.setTitle(canvasName);

            Object ctrl = loader.getController();
            if (ctrl instanceof org.example.canvasproject.controller controller) {
                controller.setCanvasSize(width, height);
                controller.initFromServer(msg.optJSONArray("elements"));
            }
        } catch (Exception e) {
            showStatus("Ошибка открытия: " + e.getMessage(), true);
        }
    }


    private VBox buildCard(CanvasItem item) {
        VBox card = new VBox(6);
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(140);
        card.setPrefHeight(110);
        card.setPadding(new Insets(12));
        card.getStyleClass().add("canvas-card");

        Label nameLabel = new Label(item.name);
        nameLabel.getStyleClass().add("canvas-card-title");
        nameLabel.setMaxWidth(120);
        nameLabel.setWrapText(true);

        Label sizeLabel = new Label(item.width + "×" + item.height);
        sizeLabel.getStyleClass().add("canvas-card-meta");

        card.getChildren().addAll(nameLabel, sizeLabel);

        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                selectCard(item.id);
                openSelected();
            } else {
                selectCard(item.id);
            }
        });

        return card;
    }

    private void selectCard(String id) {
        selectedId = id;
        for (int i = 0; i < items.size(); i++) {
            VBox card = (VBox) cardsPane.getChildren().get(i);
            CanvasItem item = items.get(i);
            boolean isSelected = item.id.equals(id);
            card.getStyleClass().removeAll("canvas-card", "canvas-card-selected");
            card.getStyleClass().add(isSelected ? "canvas-card-selected" : "canvas-card");
            if (card.getChildren().size() >= 2) {
                Label n = (Label) card.getChildren().get(0);
                Label m = (Label) card.getChildren().get(1);
                n.getStyleClass().removeAll("canvas-card-title", "canvas-card-title-selected");
                m.getStyleClass().removeAll("canvas-card-meta",  "canvas-card-meta-selected");
                n.getStyleClass().add(isSelected ? "canvas-card-title-selected" : "canvas-card-title");
                m.getStyleClass().add(isSelected ? "canvas-card-meta-selected"  : "canvas-card-meta");
            }
        }
    }


    private void showStatus(String msg, boolean isError) {
        statusLabel.setText(msg);
        statusLabel.setStyle(isError ? "-fx-text-fill:#ff6b6b;" : "-fx-text-fill:#aaaaaa;");
        statusLabel.setVisible(true);
    }


    record CanvasItem(String id, String name, int width, int height, long updatedAt) {}
}
