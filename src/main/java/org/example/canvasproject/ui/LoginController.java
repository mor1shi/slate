package org.example.canvasproject.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.example.canvasproject.collab.CollaborationClient;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONObject;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class LoginController {

    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label         errorLabel;
    @FXML private Button        loginBtn;
    @FXML private Button        registerLink;
    @FXML private CheckBox      rememberLoginCheckBox;

    private static final String SERVER_URL = "ws://localhost:8765";
    private static final String REMEMBERED_USERNAME_KEY = "rememberedUsername";
    private final Preferences preferences = Preferences.userNodeForPackage(LoginController.class);

    @FXML
    public void initialize() {
        errorLabel.setVisible(false);
        String rememberedUsername = preferences.get(REMEMBERED_USERNAME_KEY, "");
        usernameField.setText(rememberedUsername);
        rememberLoginCheckBox.setSelected(!rememberedUsername.isBlank());
        rememberLoginCheckBox.setTooltip(
            new Tooltip("Сохраняется только имя пользователя, пароль не запоминается."));
        rememberLoginCheckBox.setOnAction(e ->
            updateRememberedUsername(usernameField.getText()));

        setupMessageHandler();

        if (!SessionManager.getInstance().isConnected()) {
            connectToServer();
        } else {
            loginBtn.setDisable(false);
        }

        loginBtn.setOnAction(e -> doLogin());
        registerLink.setOnAction(e -> openRegister());
        passwordField.setOnAction(e -> doLogin());
    }

    private void setupMessageHandler() {
        SessionManager session = SessionManager.getInstance();
        if (session.getClient() == null) return;
        session.getClient().setMessageHandler(msg -> {
            String type = msg.optString("type");
            switch (type) {
                case "LOGIN_OK" -> onLoginSuccess(msg);
                case "ERROR"    -> showError(msg.optString("message", "Неверный логин или пароль"));
            }
        });
    }

    private void connectToServer() {
        loginBtn.setDisable(true);
        try {
            CollaborationClient client = new CollaborationClient(SERVER_URL);

            client.setOnConnected(() -> {
                errorLabel.setVisible(false);
                loginBtn.setDisable(false);
                setupMessageHandler();
            });

            client.setOnDisconnected(reason -> {
                showError("Нет подключения к серверу. Нажмите «Войти» чтобы повторить.");
                loginBtn.setDisable(false);
            });

            SessionManager.getInstance().setClient(client);
            client.setMessageHandler(msg -> {
                String type = msg.optString("type");
                switch (type) {
                    case "LOGIN_OK" -> onLoginSuccess(msg);
                    case "ERROR"    -> showError(msg.optString("message", "Неверный логин или пароль"));
                }
            });
            client.connect();

        } catch (Exception e) {
            showError("Не удалось подключиться: " + e.getMessage());
            loginBtn.setDisable(false);
        }
    }

    private void doLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Введите логин и пароль");
            return;
        }

        SessionManager session = SessionManager.getInstance();

        if (!session.isConnected()) {
            connectToServer();
            showError("Переподключение к серверу...");
            return;
        }

        setupMessageHandler();
        session.getClient().login(username, password);
    }

    private void onLoginSuccess(JSONObject msg) {
        String token    = msg.optString("token");
        String userId   = msg.optString("userId");
        String username = msg.optString("username");

        updateRememberedUsername(username.isBlank() ? usernameField.getText() : username);
        SessionManager.getInstance().onLoginSuccess(token, userId, username);
        openCanvasList();
    }

    private void updateRememberedUsername(String username) {
        try {
            if (rememberLoginCheckBox.isSelected() && username != null && !username.isBlank()) {
                preferences.put(REMEMBERED_USERNAME_KEY, username.trim());
            } else {
                preferences.remove(REMEMBERED_USERNAME_KEY);
            }
            preferences.flush();
        } catch (BackingStoreException | SecurityException ignored) {
        }
    }

    private void openCanvasList() {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/canvas-list.fxml")
            );
            AnchorPane root = loader.load();
            Stage stage = (Stage) loginBtn.getScene().getWindow();
            stage.setScene(new Scene(root, 960, 640));
            stage.setTitle("Мои канвасы");
            stage.setResizable(true);
            stage.setMinWidth(700);
            stage.setMinHeight(480);
        } catch (Exception e) {
            showError("Ошибка открытия списка канвасов: " + e.getMessage());
        }
    }

    private void openRegister() {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/register.fxml")
            );
            AnchorPane root = loader.load();
            Stage stage = (Stage) registerLink.getScene().getWindow();
            stage.setScene(new Scene(root, 520, 520));
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
