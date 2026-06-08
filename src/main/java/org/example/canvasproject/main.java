package org.example.canvasproject;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public class main extends Application {

    @Override
    public void start(Stage stage) throws Exception{

        //активация гпу для производительности
        System.setProperty("prism.order", "d3d");
        System.setProperty("prism.forceGPU", "true");

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/canvas-view.fxml")
        );

        AnchorPane root = loader.load();
        Scene scene = new Scene(root, 1000, 800);
        // scene.getStylesheets().add(0, "src/main/resources/style.css");

        stage.setTitle("canvas app");
        stage.setScene(scene);
        stage.show();
    }

    public static void main (String[] args) {
        launch(args);
    }
}
