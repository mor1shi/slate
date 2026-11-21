package org.example.canvasproject.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.example.canvasproject.collab.CollaborationClient;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONObject;

/**
 * Контроллер экрана входа.
 *
 * Экран логина открывается вместо main canvas-view при старте приложения.
 * После успешного LOGIN_OK открывается CanvasListController.
 */
public class LoginController {

    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label         errorLabel;
    @FXML private Button        loginBtn;
    @FXML private Button        registerLink;

    private static final String SERVER_URL = "ws://localhost:8765";

    @FXML
    public void initialize() {
        errorLabel.setVisible(false);

        // Подключаемся к серверу при открытии экрана
        connectToServer();

        loginBtn.setOnAction(e -> doLogin());
        registerLink.setOnAction(e -> openRegister());

        // Enter — войти
        passwordField.setOnAction(e -> doLogin());
    }

    private void connectToServer() {
        SessionManager session = SessionManager.getInstance();
        if (session.isConnected()) return;

        try {
            CollaborationClient client = new CollaborationClient(SERVER_URL);

            client.setMessageHandler(msg -> {
                String type = msg.optString("type");
                switch (type) {
                    case "LOGIN_OK"    -> onLoginSuccess(msg);
                    case "ERROR"       -> showError(msg.optString("message", "Неизвестная ошибка"));
                }
            });

            client.setOnConnected(() -> {
                errorLabel.setVisible(false);
                loginBtn.setDisable(false);
            });

            client.setOnDisconnected(reason -> {
                showError("Нет подключения к серверу: " + reason);
                loginBtn.setDisable(true);
            });

            client.connect();
            session.setClient(client);
            loginBtn.setDisable(true); // ждём подключения

        } catch (Exception e) {
            showError("Не удалось подключиться к серверу: " + e.getMessage());
        }
    }

    private void doLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Введите логин и пароль");
            return;
        }

        SessionManager.getInstance().getClient().login(username, password);
    }

    private void onLoginSuccess(JSONObject msg) {
        String token    = msg.optString("token");
        String userId   = msg.optString("userId");
        String username = msg.optString("username");

        SessionManager.getInstance().onLoginSuccess(token, userId, username);
        openCanvasList();
    }

    private void openCanvasList() {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/org/example/canvasproject/canvas-list.fxml")
            );
            AnchorPane root = loader.load();
            Stage stage = (Stage) loginBtn.getScene().getWindow();
            stage.setScene(new Scene(root, 700, 500));
            stage.setTitle("Мои канвасы");
        } catch (Exception e) {
            showError("Ошибка открытия списка канвасов: " + e.getMessage());
        }
    }

    private void openRegister() {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/org/example/canvasproject/register.fxml")
            );
            AnchorPane root = loader.load();
            Stage stage = (Stage) registerLink.getScene().getWindow();
            stage.setScene(new Scene(root, 450, 500));
            stage.setTitle("Регистрация");
        } catch (Exception e) {
            showError("Ошибка: " + e.getMessage());
        }
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
    }
}
