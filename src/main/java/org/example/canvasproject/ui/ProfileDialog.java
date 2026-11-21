package org.example.canvasproject.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.example.canvasproject.collab.SessionManager;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Профиль пользователя: аватар, имя и цвет, которым вы выделяетесь
 * у других участников на доске.
 */
public class ProfileDialog {

    private static final String[] PALETTE = {
            "#FF3B30", "#FF9500", "#FFD60A", "#34C759",
            "#00C7BE", "#5AC8FA", "#AF52DE", "#FF2D55"
    };

    private static final int AVATAR_SIZE = 160;

    public static void show(Window owner, Runnable onSaved) {
        SessionManager session = SessionManager.getInstance();
        if (!session.isLoggedIn() || session.getClient() == null) {
            new Alert(Alert.AlertType.WARNING, "Нет связи с сервером. Попробуйте позже.").showAndWait();
            return;
        }

        String[] selectedColor = { session.getColor().isBlank() ? PALETTE[0] : session.getColor() };
        String[] avatarData    = { session.getAvatar() };

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Профиль");
        dialog.setHeaderText("Как вас видят другие");
        dialog.getDialogPane().getStylesheets().add(
                ProfileDialog.class.getResource("/style-auth.css").toExternalForm());
        dialog.getDialogPane().setGraphic(null);

        /* --- аватар ------------------------------------------------------ */
        ImageView avatarView = new ImageView();
        avatarView.setFitWidth(72);
        avatarView.setFitHeight(72);
        avatarView.setPreserveRatio(true);
        avatarView.setSmooth(true);

        Label avatarFallback = new Label(initials(session.getUsername()));
        avatarFallback.getStyleClass().add("avatar-fallback");

        StackPane avatarFrame = new StackPane(avatarFallback, avatarView);
        avatarFrame.setPrefSize(76, 76);
        avatarFrame.setMaxSize(76, 76);
        avatarFrame.getStyleClass().add("avatar-frame");
        applyAvatar(avatarData[0], avatarView, avatarFallback, session.getUsername());

        Button uploadBtn = new Button("Загрузить фото");
        uploadBtn.getStyleClass().add("secondary-btn");

        Button removeBtn = new Button("Убрать");
        removeBtn.getStyleClass().add("secondary-btn");

        uploadBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Фото профиля");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Изображения", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
            File file = chooser.showOpenDialog(dialog.getOwner());
            if (file == null) return;
            try {
                if (file.length() > 8 * 1024 * 1024) {
                    new Alert(Alert.AlertType.WARNING, "Файл больше 8 МБ. Выберите изображение поменьше.").showAndWait();
                    return;
                }
                String data = encodeAvatar(file);
                if (data == null) {
                    new Alert(Alert.AlertType.WARNING, "Не удалось прочитать изображение.").showAndWait();
                    return;
                }
                avatarData[0] = data;
                applyAvatar(data, avatarView, avatarFallback, session.getUsername());
            } catch (Exception ex) {
                new Alert(Alert.AlertType.WARNING, "Ошибка загрузки: " + ex.getMessage()).showAndWait();
            }
        });

        removeBtn.setOnAction(e -> {
            avatarData[0] = "";
            applyAvatar("", avatarView, avatarFallback, session.getUsername());
        });

        HBox avatarRow = new HBox(14, avatarFrame, new VBox(6, uploadBtn, removeBtn));
        avatarRow.setAlignment(Pos.CENTER_LEFT);

        /* --- имя --------------------------------------------------------- */
        TextField nameField = new TextField(session.getUsername());
        nameField.getStyleClass().add("profile-field");
        nameField.setPromptText("Ваше имя");

        /* --- цвет -------------------------------------------------------- */
        ToggleGroup colorGroup = new ToggleGroup();
        HBox swatches = new HBox(8);
        for (String hex : PALETTE) {
            ToggleButton swatch = new ToggleButton();
            swatch.setToggleGroup(colorGroup);
            swatch.setUserData(hex);
            swatch.getStyleClass().add("color-swatch");
            swatch.setStyle("-fx-background-color: " + hex + ";");
            if (hex.equalsIgnoreCase(selectedColor[0])) swatch.setSelected(true);
            swatches.getChildren().add(swatch);
        }

        ColorPicker customPicker = new ColorPicker(Color.web(selectedColor[0]));
        customPicker.getStyleClass().add("color-picker");

        colorGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle != null) {
                selectedColor[0] = String.valueOf(toggle.getUserData());
                customPicker.setValue(Color.web(selectedColor[0]));
            }
        });
        customPicker.setOnAction(e -> {
            selectedColor[0] = toHex(customPicker.getValue());
            colorGroup.selectToggle(null);
        });

        /* --- превью ------------------------------------------------------ */
        Label previewName = new Label(session.getUsername());
        previewName.getStyleClass().add("cursor-preview");
        Runnable refreshPreview = () -> {
            String name = nameField.getText().isBlank() ? "Вы" : nameField.getText().trim();
            previewName.setText(name);
            previewName.setStyle("-fx-background-color: " + selectedColor[0] + ";"
                    + "-fx-text-fill: " + inkFor(selectedColor[0]) + ";");
        };
        nameField.textProperty().addListener((o, a, b) -> refreshPreview.run());
        customPicker.valueProperty().addListener((o, a, b) -> {
            selectedColor[0] = toHex(b);
            refreshPreview.run();
        });
        refreshPreview.run();

        Label previewHint = new Label("Так вас увидят другие на доске");
        previewHint.getStyleClass().add("meta-label");

        /* --- сохранение -------------------------------------------------- */
        Label feedback = new Label();
        feedback.getStyleClass().add("success-label");
        feedback.setVisible(false);
        feedback.setManaged(false);

        Button saveBtn = new Button("Сохранить");
        saveBtn.getStyleClass().add("primary-btn");
        saveBtn.setMaxWidth(Double.MAX_VALUE);

        Consumer<JSONObject> listener = msg -> {
            String type = msg.optString("type");
            if ("PROFILE_UPDATED".equals(type)) {
                session.setProfile(msg.optString("username"), msg.optString("color"), msg.optString("avatar"));
                if (onSaved != null) onSaved.run();
                dialog.close();
            } else if ("ERROR".equals(type)) {
                feedback.getStyleClass().setAll("error-label");
                feedback.setText(msg.optString("message", "Не удалось сохранить"));
                feedback.setVisible(true);
                feedback.setManaged(true);
            }
        };
        session.addMessageListener(listener);

        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.length() < 1) {
                feedback.getStyleClass().setAll("error-label");
                feedback.setText("Имя не может быть пустым");
                feedback.setVisible(true);
                feedback.setManaged(true);
                return;
            }
            session.getClient().updateProfile(name, selectedColor[0], avatarData[0]);
        });

        VBox nameBox = new VBox(6, new Label("Имя"), nameField);
        VBox colorBox = new VBox(8, new Label("Цвет на доске"), swatches,
                new HBox(8, new Label("Другой:"), customPicker));
        ((HBox) colorBox.getChildren().get(2)).setAlignment(Pos.CENTER_LEFT);

        VBox previewBox = new VBox(6, previewHint, previewName);
        previewBox.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(18, avatarRow, nameBox, colorBox, previewBox, saveBtn, feedback);
        content.setPadding(new Insets(16, 4, 4, 4));
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Runnable restore = () -> session.removeMessageListener(listener);
        dialog.setOnCloseRequest(e -> restore.run());
        dialog.showAndWait();
        restore.run();
    }

    /* ------------------------------------------------------------------ */

    private static void applyAvatar(String dataUrl, ImageView view, Label fallback, String username) {
        fallback.setText(initials(username));
        if (dataUrl == null || dataUrl.isBlank()) {
            view.setImage(null);
            view.setVisible(false);
            fallback.setVisible(true);
            return;
        }
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("bad data URL");
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            Image image = new Image(new ByteArrayInputStream(bytes), 76, 76, true, true);
            if (image.isError()) throw new IllegalArgumentException("bad image");
            view.setImage(image);
            view.setVisible(true);
            fallback.setVisible(false);
        } catch (Exception e) {
            view.setImage(null);
            view.setVisible(false);
            fallback.setVisible(true);
        }
    }

    private static String encodeAvatar(File file) throws Exception {
        java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(file);
        if (source == null) return null;

        int side = Math.min(source.getWidth(), source.getHeight());
        int offsetX = (source.getWidth() - side) / 2;
        int offsetY = (source.getHeight() - side) / 2;

        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(AVATAR_SIZE, AVATAR_SIZE,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, AVATAR_SIZE, AVATAR_SIZE,
                offsetX, offsetY, offsetX + side, offsetY + side, null);
        g.dispose();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "png", bytes);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    /** Контрастный цвет текста для метки курсора. */
    private static String inkFor(String hex) {
        try {
            Color c = Color.web(hex);
            double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
            return lum > 0.62 ? "#1A1A1A" : "#FFFFFF";
        } catch (Exception e) {
            return "#FFFFFF";
        }
    }

    private ProfileDialog() {}
}
