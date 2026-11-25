package org.example.canvasproject;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.example.canvasproject.collab.SessionManager;
import org.example.canvasproject.storage.LocalBoard;
import org.example.canvasproject.storage.LocalBoardService;
import org.example.canvasproject.ui.CanvasListController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Точка входа. Регистрации нет: устройство само получает ключ,
 * экран входа открывается сразу как список досок.
 *
 * <p>Если приложение запущено с путём к `.slate` (двойной клик по файлу или
 * ассоциация), доска импортируется до показа списка и сразу открывается.
 */
public class main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Path slateFile = slateFileArgument();
        LocalBoard imported = null;
        Exception importError = null;
        if (slateFile != null) {
            try {
                imported = LocalBoardService.getInstance().importBoard(slateFile);
            } catch (Exception e) {
                importError = e;
            }
        }

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/canvas-list.fxml")
        );

        AnchorPane root = loader.load();
        Scene scene = new Scene(root, 980, 660);
        stage.setTitle("Slate — Мои доски");
        stage.setScene(scene);
        org.example.canvasproject.ui.WindowState.restore(stage);
        stage.setOnCloseRequest(e -> org.example.canvasproject.ui.WindowState.save(stage));

        var iconUrl = getClass().getResource("/icons/slate.png");
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }

        stage.setResizable(true);
        stage.setMinWidth(820);
        stage.setMinHeight(540);
        stage.show();

        CanvasListController controller = loader.getController();
        if (imported != null) {
            controller.openBoardById(imported.getId());
        } else if (importError != null) {
            controller.showImportError(importError);
        }
    }

    /** Первый аргумент командной строки, указывающий на существующий .slate. */
    private Path slateFileArgument() {
        for (String arg : getParameters().getRaw()) {
            if (arg == null || !arg.toLowerCase(Locale.ROOT).endsWith(".slate")) continue;
            Path path = Paths.get(arg);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
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
