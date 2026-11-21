package org.example.canvasproject.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.example.canvasproject.collab.SessionManager;
import org.example.canvasproject.i18n.I18n;
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
        dialog.setTitle(I18n.t("dialog.invite.title"));
        dialog.setHeaderText(I18n.t("dialog.invite.header"));
        dialog.getDialogPane().getStylesheets().add(
                InviteCodeDialog.class.getResource("/style-auth.css").toExternalForm());
        dialog.getDialogPane().setGraphic(null);

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);
        content.setPrefWidth(360);

        Label hint = new Label(I18n.t("dialog.invite.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("hint-text");

        Label roleLabel = new Label(I18n.t("dialog.invite.role"));
        roleLabel.getStyleClass().add("meta-label");

        ToggleGroup roleGroup = new ToggleGroup();
        ToggleButton editorBtn = new ToggleButton(I18n.t("role.editor"));
        editorBtn.setUserData("editor");
        editorBtn.setToggleGroup(roleGroup);
        editorBtn.getStyleClass().add("role-toggle");
        ToggleButton viewerBtn = new ToggleButton(I18n.t("role.viewer"));
        viewerBtn.setUserData("viewer");
        viewerBtn.setToggleGroup(roleGroup);
        viewerBtn.getStyleClass().add("role-toggle");

        if (session.isOwner()) {
            editorBtn.setSelected(true);
            roleGroup.selectToggle(editorBtn);
        } else {
            editorBtn.setVisible(false);
            editorBtn.setManaged(false);
            roleGroup.selectToggle(viewerBtn);
        }
        HBox roles = new HBox(0, editorBtn, viewerBtn);
        roles.setAlignment(Pos.CENTER);

        Label codeLabel = new Label("——");
        codeLabel.getStyleClass().add("code-label");

        Label expiryLabel = new Label();
        expiryLabel.getStyleClass().add("meta-label");

        Button genBtn  = new Button(I18n.t("dialog.invite.generate"));
        Button copyBtn = new Button(I18n.t("dialog.invite.copy"));
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
                String roleText = "editor".equals(role)
                        ? I18n.t("role.editor") : I18n.t("role.viewer");
                String expStr = expires > 0
                        ? I18n.t("dialog.invite.roleLabel", roleText)
                          + "\n"
                          + I18n.t("dialog.invite.expires",
                              new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(expires)))
                        : "";
                expiryLabel.setText(expStr);
                copyBtn.setDisable(false);
                copyBtn.setVisible(true);
            } else if ("ERROR".equals(type)) {
                codeLabel.setText("—");
                expiryLabel.setText(msg.optString("message"));
            }
        };
        session.addMessageListener(listener);

        genBtn.setOnAction(e -> {
            codeLabel.setText("…");
            String role = roleGroup.getSelectedToggle() == editorBtn ? "editor" : "viewer";
            session.getClient().generateInviteCode(session.getActiveCanvasId(), role, 24, 1);
        });

        copyBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(codeLabel.getText());
            clipboard.setContent(cc);
            copyBtn.setText(I18n.t("dialog.invite.copied"));
            PauseTransition pause = new PauseTransition(Duration.seconds(2));
            pause.setOnFinished(ev -> copyBtn.setText(I18n.t("dialog.invite.copy")));
            pause.play();
        });

        content.getChildren().addAll(hint, roleLabel, roles, codeLabel, expiryLabel, genBtn, copyBtn);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType(
                I18n.t("button.close"), ButtonBar.ButtonData.CANCEL_CLOSE));

        dialog.setOnCloseRequest(e -> session.removeMessageListener(listener));

        dialog.showAndWait();
        session.removeMessageListener(listener);
    }
}
