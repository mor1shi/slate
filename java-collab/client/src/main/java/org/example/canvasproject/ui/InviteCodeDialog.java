package org.example.canvasproject.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Диалог показа/генерации инвайт-кода.
 *
 * Вызов:
 *   InviteCodeDialog.show(stage);
 */
public class InviteCodeDialog {

    public static void show(javafx.stage.Window owner) {
        SessionManager session = SessionManager.getInstance();
        if (!session.isLoggedIn() || session.getActiveCanvasId() == null) {
            new Alert(Alert.AlertType.WARNING, "Сначала откройте канвас").showAndWait();
            return;
        }

        // Создаём диалог
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Пригласить участника");
        dialog.setHeaderText("Код приглашения на канвас");

        VBox content = new VBox(14);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);
        content.setPrefWidth(340);

        Label hint = new Label("Нажмите кнопку, чтобы создать одноразовый код.\nКод действует 24 часа.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #8e8e93; -fx-font-size: 13px;");

        Label codeLabel = new Label("——");
        codeLabel.setStyle(
            "-fx-font-size: 36px; -fx-font-weight: bold; -fx-letter-spacing: 8px;" +
            "-fx-text-fill: #0a84ff; -fx-font-family: 'Courier New', monospace;"
        );

        Label expiryLabel = new Label();
        expiryLabel.setStyle("-fx-text-fill: #636366; -fx-font-size: 12px;");

        Button genBtn  = new Button("Создать код");
        Button copyBtn = new Button("Скопировать");
        copyBtn.setDisable(true);
        copyBtn.setVisible(false);

        genBtn.setStyle("-fx-background-color: #0a84ff; -fx-text-fill: white; " +
                        "-fx-background-radius: 10; -fx-font-size: 14px; -fx-padding: 10 20;");
        copyBtn.setStyle("-fx-background-color: #2c2c2e; -fx-text-fill: white; " +
                         "-fx-background-radius: 10; -fx-font-size: 13px; -fx-padding: 8 16;");

        // Ответ сервера
        session.getClient().setMessageHandler(msg -> {
            String type = msg.optString("type");
            if ("INVITE_CODE".equals(type)) {
                String code   = msg.optString("code");
                long expires  = msg.optLong("expiresAt", 0);
                codeLabel.setText(code);

                String expStr = expires > 0
                    ? "Действителен до: " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(expires))
                    : "";
                expiryLabel.setText(expStr);
                copyBtn.setDisable(false);
                copyBtn.setVisible(true);
            } else if ("ERROR".equals(type)) {
                codeLabel.setText("Ошибка");
                expiryLabel.setText(msg.optString("message"));
            }
        });

        genBtn.setOnAction(e -> {
            codeLabel.setText("Генерация...");
            session.getClient().generateInviteCode(session.getActiveCanvasId());
        });

        copyBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(codeLabel.getText());
            clipboard.setContent(cc);
            copyBtn.setText("Скопировано!");
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2))
                {{setOnFinished(ev -> copyBtn.setText("Скопировать"));}}
                .play();
        });

        content.getChildren().addAll(hint, codeLabel, expiryLabel, genBtn, copyBtn);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setStyle("-fx-background-color: #1c1c1e;");

        dialog.showAndWait();
    }
}
