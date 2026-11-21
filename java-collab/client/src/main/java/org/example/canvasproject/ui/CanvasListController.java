package org.example.canvasproject.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Optional;

/**
 * Контроллер экрана выбора канваса.
 *
 * Показывает список канвасов пользователя.
 * Позволяет: создать новый, войти по коду, открыть существующий.
 */
public class CanvasListController {

    @FXML private Label     welcomeLabel;
    @FXML private ListView<CanvasItem> canvasList;
    @FXML private Button    createBtn;
    @FXML private Button    joinByCodeBtn;
    @FXML private Button    openBtn;
    @FXML private Button    logoutBtn;
    @FXML private Label     statusLabel;

    private final ObservableList<CanvasItem> items = FXCollections.observableArrayList();
    private SessionManager session = SessionManager.getInstance();

    @FXML
    public void initialize() {
        welcomeLabel.setText("Привет, " + session.getUsername() + "!");
        canvasList.setItems(items);
        canvasList.setCellFactory(lv -> new CanvasCell());
        statusLabel.setVisible(false);

        // Обработчик сообщений сервера
        session.getClient().setMessageHandler(msg -> {
            String type = msg.optString("type");
            switch (type) {
                case "CANVAS_LIST"   -> populateList(msg.optJSONArray("canvases"));
                case "CANVAS_CREATED"-> onCanvasCreated(msg.optJSONObject("canvas"));
                case "JOINED_CANVAS" -> openCanvas(msg);
                case "ERROR"         -> showStatus("Ошибка: " + msg.optString("message"), true);
            }
        });

        // Двойной клик — открыть
        canvasList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && canvasList.getSelectionModel().getSelectedItem() != null) {
                openSelected();
            }
        });

        createBtn.setOnAction(e -> createCanvas());
        joinByCodeBtn.setOnAction(e -> joinByCode());
        openBtn.setOnAction(e -> openSelected());
        logoutBtn.setOnAction(e -> logout());

        // Загружаем список
        session.getClient().listCanvases();
    }

    // ─────────────────── HANDLERS ───────────────────

    private void createCanvas() {
        TextInputDialog dialog = new TextInputDialog("Мой канвас");
        dialog.setTitle("Новый канвас");
        dialog.setHeaderText("Создать новый канвас");
        dialog.setContentText("Название:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                session.getClient().createCanvas(name);
            }
        });
    }

    private void joinByCode() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Присоединиться по коду");
        dialog.setHeaderText("Введите код приглашения");
        dialog.setContentText("Код (6 символов):");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(code -> {
            if (!code.isBlank()) {
                session.getClient().joinByCode(code.trim().toUpperCase());
            }
        });
    }

    private void openSelected() {
        CanvasItem selected = canvasList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("Выберите канвас из списка", false);
            return;
        }
        session.getClient().joinCanvas(selected.id());
    }

    private void logout() {
        session.getClient().logout();
        session.onLogout();
        openLogin();
    }

    // ─────────────────── SERVER RESPONSES ───────────────────

    private void populateList(JSONArray arr) {
        items.clear();
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            items.add(new CanvasItem(
                c.optString("id"),
                c.optString("name", "Без названия"),
                c.optInt("memberCount", 1),
                c.optLong("updatedAt", 0)
            ));
        }
        if (items.isEmpty()) showStatus("У вас пока нет канвасов. Создайте первый!", false);
    }

    private void onCanvasCreated(JSONObject canvas) {
        if (canvas == null) return;
        session.getClient().listCanvases(); // обновляем список
        showStatus("Канвас \"" + canvas.optString("name") + "\" создан!", false);
    }

    private void openCanvas(JSONObject msg) {
        String canvasId   = msg.optString("canvasId");
        String canvasName = msg.optString("canvasName", "Канвас");

        session.setActiveCanvas(canvasId, canvasName);

        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/org/example/canvasproject/canvas-view.fxml")
            );
            AnchorPane root = loader.load();
            Stage stage = (Stage) createBtn.getScene().getWindow();
            Scene scene = new Scene(root, 1200, 800);
            stage.setScene(scene);
            stage.setTitle(canvasName);

            // Передаём элементы с сервера в контроллер
            Object ctrl = loader.getController();
            if (ctrl instanceof org.example.canvasproject.controller controller) {
                controller.initFromServer(msg.optJSONArray("elements"));
            }
        } catch (Exception e) {
            showStatus("Ошибка открытия канваса: " + e.getMessage(), true);
        }
    }

    // ─────────────────── NAVIGATION ───────────────────

    private void openLogin() {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/org/example/canvasproject/login.fxml")
            );
            AnchorPane root = loader.load();
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            stage.setScene(new Scene(root, 450, 400));
            stage.setTitle("Вход");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showStatus(String msg, boolean isError) {
        statusLabel.setText(msg);
        statusLabel.setStyle(isError ? "-fx-text-fill: #ff3b30;" : "-fx-text-fill: #34c759;");
        statusLabel.setVisible(true);
    }

    // ─────────────────── CANVAS ITEM & CELL ───────────────────

    public record CanvasItem(String id, String name, int memberCount, long updatedAt) {}

    static class CanvasCell extends ListCell<CanvasItem> {
        @Override
        protected void updateItem(CanvasItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm");
                String date = item.updatedAt() > 0
                    ? sdf.format(new java.util.Date(item.updatedAt()))
                    : "—";
                setText(item.name() + "   ·   " + item.memberCount() + " участн.   ·   " + date);
            }
        }
    }
}
