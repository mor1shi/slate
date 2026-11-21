package org.example.canvasproject.ui;

import javafx.stage.Stage;

import java.util.prefs.Preferences;

/** Запоминает размер, позицию и максимизацию единственного окна. */
public final class WindowState {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(WindowState.class);
    private static final double MIN_W = 820;
    private static final double MIN_H = 540;

    private WindowState() {}

    public static void restore(Stage stage) {
        double w = PREFS.getDouble("width", 0);
        double h = PREFS.getDouble("height", 0);
        if (w >= MIN_W && h >= MIN_H) {
            stage.setWidth(w);
            stage.setHeight(h);
        }
        double x = PREFS.getDouble("x", Double.NaN);
        double y = PREFS.getDouble("y", Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            stage.setX(x);
            stage.setY(y);
        }
        stage.setMaximized(PREFS.getBoolean("maximized", false));
    }

    public static void save(Stage stage) {
        if (!stage.isMaximized()) {
            PREFS.putDouble("width", stage.getWidth());
            PREFS.putDouble("height", stage.getHeight());
            PREFS.putDouble("x", stage.getX());
            PREFS.putDouble("y", stage.getY());
        }
        PREFS.putBoolean("maximized", stage.isMaximized());
    }
}
