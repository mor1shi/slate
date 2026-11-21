package org.example.canvasproject;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.example.canvasproject.collab.SessionManager;

/**
 * Обновлённый main.java — теперь открывает экран логина.
 * Замените оригинальный файл этим.
 */
public class main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        System.setProperty("prism.order", "d3d");
        System.setProperty("prism.forceGPU", "true");

        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/org/example/canvasproject/login.fxml")
        );

        AnchorPane root = loader.load();
        Scene scene = new Scene(root, 450, 400);
        stage.setTitle("Canvas App — Вход");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        // Закрываем WebSocket соединение при выходе
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
