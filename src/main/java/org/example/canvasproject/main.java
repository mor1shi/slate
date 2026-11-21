package org.example.canvasproject;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.example.canvasproject.collab.SessionManager;

/**
 * Точка входа. Регистрации нет: устройство само получает ключ,
 * экран входа открывается сразу как список досок.
 */
public class main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/canvas-list.fxml")
        );

        AnchorPane root = loader.load();
        Scene scene = new Scene(root, 980, 660);
        stage.setTitle("Slate — Мои доски");
        stage.setScene(scene);

        var iconUrl = getClass().getResource("/icons/slate.png");
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }

        stage.setResizable(true);
        stage.setMinWidth(820);
        stage.setMinHeight(540);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        SessionManager session = SessionManager.getInstance();
        if (session.getClient() != null && session.getClient().isOpen()) {
            session.getClient().close();
        }
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
