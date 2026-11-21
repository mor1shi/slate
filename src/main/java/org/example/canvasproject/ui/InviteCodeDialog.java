package org.example.canvasproject.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;

public class InviteCodeDialog {

    public static void show(javafx.stage.Window owner) {
        SessionManager session = SessionManager.getInstance();
        if (!session.isLoggedIn() || session.getActiveCanvasId() == null) {
            new Alert(Alert.AlertType.WARNING, "Сначала откройте канвас").showAndWait();
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Пригласить участника");
        dialog.setHeaderText("Код приглашения на доску");
        dialog.getDialogPane().getStylesheets().add(
                InviteCodeDialog.class.getResource("/style-auth.css").toExternalForm());
        dialog.getDialogPane().setGraphic(null);

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);
        content.setPrefWidth(360);

        Label hint = new Label("Одноразовый код, действует 24 часа.\n"
                + "Передайте его человеку, которого хотите пригласить.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint-text");

        ChoiceBox<String> roleChoice = new ChoiceBox<>();
        roleChoice.getItems().add("Только просмотр");
        if (session.isOwner()) {
            roleChoice.getItems().add("Редактор");
        }
        roleChoice.getSelectionModel().selectFirst();
        if (session.isOwner()) {
            roleChoice.setValue("Редактор");
        }

        Label codeLabel = new Label("——");
        codeLabel.getStyleClass().add("code-label");

        Label expiryLabel = new Label();
        expiryLabel.getStyleClass().add("meta-label");

        Button genBtn  = new Button("Создать код");
        Button copyBtn = new Button("Скопировать");
        copyBtn.setDisable(true);
        copyBtn.setVisible(false);

        genBtn.getStyleClass().add("primary-btn");
        copyBtn.getStyleClass().add("secondary-btn");

        Consumer<JSONObject> listener = msg -> {
            String type = msg.optString("type");
            if ("INVITE_CODE".equals(type)) {
                String code  = msg.optString("code");
                long expires = msg.optLong("expiresAt", 0);
                String role  = msg.optString("role", "viewer");
                codeLabel.setText(code);
                String expStr = expires > 0
                        ? "Роль: " + ("editor".equals(role) ? "редактор" : "только просмотр")
                          + "\nДействителен до: "
                          + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(expires))
                        : "";
                expiryLabel.setText(expStr);
                copyBtn.setDisable(false);
                copyBtn.setVisible(true);
            } else if ("ERROR".equals(type)) {
                codeLabel.setText("Ошибка");
                expiryLabel.setText(msg.optString("message"));
            }
        };
        session.addMessageListener(listener);

        genBtn.setOnAction(e -> {
            codeLabel.setText("Генерация…");
            String role = "Редактор".equals(roleChoice.getValue()) ? "editor" : "viewer";
            session.getClient().generateInviteCode(session.getActiveCanvasId(), role, 24, 1);
        });

        copyBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(codeLabel.getText());
            clipboard.setContent(cc);
            copyBtn.setText("Скопировано!");
            PauseTransition pause = new PauseTransition(Duration.seconds(2));
            pause.setOnFinished(ev -> copyBtn.setText("Скопировать"));
            pause.play();
        });

        content.getChildren().addAll(hint, roleChoice, codeLabel, expiryLabel, genBtn, copyBtn);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        dialog.setOnCloseRequest(e -> session.removeMessageListener(listener));

        dialog.showAndWait();
        session.removeMessageListener(listener);
    }
}
