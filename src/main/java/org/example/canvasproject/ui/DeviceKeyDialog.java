package org.example.canvasproject.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.canvasproject.collab.DeviceIdentity;

/**
 * Диалог ключа устройства: показать, скопировать, перенести на другое устройство.
 */
public class DeviceKeyDialog {

    public static void show(Window owner, Runnable onIdentityChanged) {
        DeviceIdentity identity = DeviceIdentity.getInstance();

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Ключ устройства");
        dialog.setHeaderText("Это устройство");
        dialog.getDialogPane().getStylesheets().add(
                DeviceKeyDialog.class.getResource("/style-auth.css").toExternalForm());

        Label explain = new Label(
                "Ключ заменяет логин и пароль. Он хранится только на этом компьютере " +
                "(~/.config/canvasproject/device.json) и определяет, какие доски ваши.\n\n" +
                "Чтобы открыть те же доски на другом компьютере, вставьте этот ключ там.");
        explain.setWrapText(true);
        explain.getStyleClass().add("hint-text");

        TextArea keyField = new TextArea(identity.getKey());
        keyField.setEditable(false);
        keyField.setWrapText(true);
        keyField.setPrefRowCount(2);
        keyField.getStyleClass().add("device-key-field");

        Label nameLabel = new Label("Имя устройства: " + identity.getName());
        nameLabel.getStyleClass().add("meta-label");

        Button copyBtn = new Button("Скопировать ключ");
        copyBtn.getStyleClass().add("primary-btn");
        copyBtn.setMaxWidth(Double.MAX_VALUE);

        Button useOtherBtn = new Button("Вставить ключ другого устройства");
        useOtherBtn.getStyleClass().add("secondary-btn");
        useOtherBtn.setMaxWidth(Double.MAX_VALUE);

        Label feedback = new Label();
        feedback.getStyleClass().add("success-label");
        feedback.setVisible(false);
        feedback.setManaged(false);

        PauseTransition[] clipboardClear = {null};
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(identity.getKey());
            Clipboard.getSystemClipboard().setContent(cc);
            feedback.setText("Ключ скопирован. Буфер очистится через минуту");
            feedback.setVisible(true);
            feedback.setManaged(true);

            if (clipboardClear[0] != null) clipboardClear[0].stop();
            PauseTransition clear = new PauseTransition(Duration.seconds(60));
            clear.setOnFinished(ev -> {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                if (identity.getKey().equals(clipboard.getString())) {
                    clipboard.clear();
                    feedback.setText("Буфер обмена очищен");
                }
            });
            clipboardClear[0] = clear;
            clear.play();
        });

        useOtherBtn.setOnAction(e -> {
            TextInputDialog input = new TextInputDialog();
            input.initOwner(dialog.getDialogPane().getScene().getWindow());
            input.setTitle("Ключ другого устройства");
            input.setHeaderText("Вставьте ключ, чтобы открыть те же доски");
            input.setContentText("Ключ:");
            input.showAndWait().ifPresent(raw -> {
                String normalized = DeviceIdentity.normalizeKey(raw);
                if (normalized != null && !normalized.equals(identity.getKey())
                        && identity.useKey(raw)) {
                    dialog.close();
                    onIdentityChanged.run();
                } else if (normalized == null) {
                    feedback.getStyleClass().setAll("error-label");
                    feedback.setText("Ключ выглядит некорректно");
                    feedback.setVisible(true);
                    feedback.setManaged(true);
                }
            });
        });

        VBox content = new VBox(12, explain, keyField, nameLabel, copyBtn, useOtherBtn, feedback);
        content.setPadding(new Insets(16, 4, 4, 4));
        content.setPrefWidth(420);
        VBox.setVgrow(keyField, Priority.NEVER);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        dialog.showAndWait();
    }
}
