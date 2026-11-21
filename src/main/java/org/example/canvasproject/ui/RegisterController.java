package org.example.canvasproject.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONObject;

/**
 * Контроллер экрана регистрации.
 */
public class RegisterController {

    @FXML private TextField     usernameField;
    @FXML private TextField     emailField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Label         errorLabel;
    @FXML private Label         successLabel;
    @FXML private Button        registerBtn;
    @FXML private Button        backToLoginLink;

    @FXML
    public void initialize() {
        errorLabel.setVisible(false);
        successLabel.setVisible(false);

        //Подключаемся к серверу
        if (!SessionManager.getInstance().isConnected()) {
            showError("Нет подключения к серверу. Вернитесь на страницу входа.");
            registerBtn.setDisable(true);
            return;
        }

        //Навешиваем обработчик на ответы сервера
        SessionManager.getInstance().getClient().setMessageHandler(msg -> {
            String type = msg.optString("type");
            switch (type) {
                case "REGISTER_OK" -> onRegisterSuccess(msg);
                case "ERROR"       -> showError(msg.optString("message", "Ошибка регистрации"));
            }
        });

        registerBtn.setOnAction(e -> doRegister());
        backToLoginLink.setOnAction(e -> goBackToLogin());
    }

    private void doRegister() {
        String username = usernameField.getText().trim();
        String email    = emailField.getText().trim();
        String password = passwordField.getText();
        String confirm  = confirmField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Заполните обязательные поля");
            return;
        }
        if (password.length() < 6) {
            showError("Пароль должен быть не менее 6 символов");
            return;
        }
        if (!password.equals(confirm)) {
            showError("Пароли не совпадают");
            return;
        }

        SessionManager.getInstance().getClient().register(username, email, password);
    }

    private void onRegisterSuccess(JSONObject msg) {
        javafx.application.Platform.runLater(() -> {
            successLabel.setText("Аккаунт создан! Переходим к входу...");
            successLabel.setVisible(true);
            errorLabel.setVisible(false);
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                    javafx.util.Duration.seconds(1.5)
            );
            pause.setOnFinished(e -> goBackToLogin());
            pause.play();
        });
    }
    private void goBackToLogin() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/login.fxml")
            );
            AnchorPane root = loader.load();
            Stage stage = (Stage) registerBtn.getScene().getWindow();
            stage.setScene(new Scene(root, 450, 400));
            stage.setTitle("Вход");
        } catch (Exception e) {
            showError("Ошибка: " + e.getMessage());
        }
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        successLabel.setVisible(false);
    }
}
