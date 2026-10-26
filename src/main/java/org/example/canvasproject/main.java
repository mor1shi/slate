package org.example.canvasproject;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.example.canvasproject.collab.SessionManager;

public class main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/login.fxml")
        );

        AnchorPane root = loader.load();
        Scene scene = new Scene(root, 520, 480);
        stage.setTitle("Canvas App - Вход");
        stage.setScene(scene);

        stage.setResizable(true);
        stage.setMinWidth(420);
        stage.setMinHeight(360);
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
