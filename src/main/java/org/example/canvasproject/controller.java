package org.example.canvasproject;

import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.Cursor;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

public class controller {

    @FXML private Canvas canvas;
    @FXML private Button penBtn, textBtn, figureBtn, imgBtm, noteBtn;
    @FXML private AnchorPane pane;
    @FXML private ImageView imageView;

    private GraphicsContext gc;

    private boolean isPenSelected = false;
    private boolean isTextSelected = false;
    private customPopUp popup;

    private Group canvasGroup = new Group();
    private double zoomFactor = 1.0;
    private double translateX = 0;
    private double translateY = 0;
    private boolean isPanning = false;
    private double panStartX, panStartY;
    private double translateStartX, translateStartY;

    private double lastX, lastY;
    private long lastDrawTime;
    private static final double DISTANCE = 4.0;
    private static final long DELAY = 16;

    private List<customTextField> textFields = new ArrayList<>();

    @FXML
    public void initialize() {
        gc = canvas.getGraphicsContext2D();

        // заливаем канвас белым
        Platform.runLater(() -> {
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        });

        gc.setLineWidth(4);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        canvas.setCache(true);
        canvas.setCacheHint(CacheHint.SPEED);

        // Добавляем канвас в группу
        canvasGroup.getChildren().add(canvas);
        pane.getChildren().add(0, canvasGroup);

        setupZoomAndPan();
        setupCanvasInteractions();
        setupCentering();
        setupSceneZoom();

        imgBtm.setOnAction(e ->{
            handleUploadButton();
        });

        penBtn.setOnAction(e -> {
            isPenSelected = true;
            isTextSelected = false;
            buttonManager(penBtn);
            if (popup != null && popup.isShowing()) popup.hide();
            else showPopup();
        });

        textBtn.setOnAction(e -> {
            isTextSelected = true;
            isPenSelected = false;
            buttonManager(textBtn);
        });

        // анимация кнопок
        for (Button btn : new Button[]{textBtn, penBtn, figureBtn, imgBtm, noteBtn}) {
            ScaleTransition mouseIn = new ScaleTransition(Duration.millis(100), btn);
            mouseIn.setToX(1.2);
            mouseIn.setToY(1.2);
            ScaleTransition mouseOut = new ScaleTransition(Duration.millis(100), btn);
            mouseOut.setToX(1.0);
            mouseOut.setToY(1.0);
            btn.setOnMouseEntered(e -> mouseIn.playFromStart());
            btn.setOnMouseExited(e -> mouseOut.playFromStart());
        }
    }

    @FXML
    private void handleUploadButton() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Выберите фотографию");

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg")
        );
        File file = fileChooser.showOpenDialog(null);

        if (file != null) {
            try {
                FileInputStream fis = new FileInputStream(file);
                Image image = new Image(fis);
                imageView.setImage(image);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    private void setupCentering() {
        // слушаем изменение размера сцены
        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.widthProperty().addListener((w, oldW, newW) -> centerCanvas());
                newScene.heightProperty().addListener((h, oldH, newH) -> centerCanvas());
                centerCanvas();
            }
        });
    }

    private void centerCanvas() {
        if (pane.getScene() == null) return;

        double sceneWidth = pane.getScene().getWidth();
        double sceneHeight = pane.getScene().getHeight();

        // центрируем канвас
        double canvasWidth = canvas.getWidth();
        double canvasHeight = canvas.getHeight();

        // начальное смещение для центрирования
        double centerX = (sceneWidth - canvasWidth * zoomFactor) / 2;
        double centerY = (sceneHeight - canvasHeight * zoomFactor) / 2 - 30;

        translateX = centerX;
        translateY = centerY;

        updateTransform();
    }

    private void showPopup() {
        VBox settingsLayout = new VBox(15);
        settingsLayout.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-border-color: gray; -fx-border-radius: 5;");

        Slider thicknessSlider = new Slider(1, 20, gc.getLineWidth());
        thicknessSlider.valueProperty().addListener((obs, old, val) -> gc.setLineWidth(val.doubleValue()));

        HBox colorPalette = new HBox(5);
        ColorPicker colorPicker = new ColorPicker();
        colorPicker.setPrefSize(25, 25);
        colorPicker.setCursor(Cursor.HAND);
        colorPicker.setStyle("-fx-background-color: white; -fx-border: none;");
        colorPicker.setOnAction(event -> gc.setStroke(colorPicker.getValue()));
        colorPalette.getChildren().add(colorPicker);

        Color[] colors = {Color.BLACK, Color.LIGHTGRAY, Color.INDIANRED, Color.ORANGE, Color.DARKSEAGREEN, Color.DEEPSKYBLUE};
        for (Color color : colors) {
            Button colorBtn = new Button();
            colorBtn.setShape(new Circle(12.5));
            colorBtn.setPrefSize(25, 25);
            colorBtn.setCursor(Cursor.HAND);
            colorBtn.setStyle("-fx-background-color: " + toHex(color) + "; -fx-border: none;");
            colorBtn.setOnAction(event -> gc.setStroke(color));
            colorPalette.getChildren().add(colorBtn);
        }

        settingsLayout.getChildren().addAll(thicknessSlider, colorPalette);
        popup = new customPopUp(penBtn, 0, -100);
        popup.getContent().add(settingsLayout);
        popup.show(penBtn.getScene().getWindow());
    }

    private void setupZoomAndPan() {
        canvasGroup.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.MIDDLE) {
                isPanning = true;
                panStartX = e.getSceneX();
                panStartY = e.getSceneY();
                translateStartX = translateX;
                translateStartY = translateY;
                canvasGroup.setCursor(Cursor.CLOSED_HAND);
                e.consume();
            }
        });

        canvasGroup.setOnMouseDragged(e -> {
            if (isPanning) {
                translateX = translateStartX + (e.getSceneX() - panStartX);
                translateY = translateStartY + (e.getSceneY() - panStartY);

                updateTransform();
                e.consume();
            }
        });

        canvasGroup.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.MIDDLE) {
                isPanning = false;
                canvasGroup.setCursor(Cursor.DEFAULT);
                e.consume();
            }
        });
    }

    private void setupSceneZoom() {
        pane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.setOnScroll(e -> {
                    double delta = e.getDeltaY() > 0 ? 1.1 : 0.9;
                    double newZoom = zoomFactor * delta;

                    if (newZoom < 0.5) newZoom = 0.5;
                    if (newZoom > 5.0) newZoom = 5.0;
                    if (newZoom == zoomFactor) return;

                    zoomFactor = newZoom;
                    updateTransform();

                    e.consume();
                });
            }
        });
    }

    private void updateTransform() {
        canvasGroup.setScaleX(zoomFactor);
        canvasGroup.setScaleY(zoomFactor);
        canvasGroup.setTranslateX(translateX);
        canvasGroup.setTranslateY(translateY);
    }

    private void setupCanvasInteractions() {
        canvas.setOnMousePressed(e -> {
            double x = (e.getX());
            double y = (e.getY());

            if (e.getButton() == MouseButton.PRIMARY) {
                if (isTextSelected) {
                    customTextField clicked = null;
                    for (customTextField tf : textFields) {
                        if (tf.getBoundsInParent().contains(x, y)) {
                            clicked = tf;
                            break;
                        }
                    }
                    if (clicked != null) {
                        clicked.selectTextField();
                    } else {
                        customTextField newField = new customTextField(x, y);
                        canvasGroup.getChildren().add(newField);
                        textFields.add(newField);
                        newField.selectTextField();
                    }
                } else if (isPenSelected) {
                    lastX = x;
                    lastY = y;
                    gc.beginPath();
                    gc.moveTo(lastX, lastY);
                    gc.stroke();
                }
            }
        });

        canvas.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY && isPenSelected) {
                long now = System.currentTimeMillis();
                if (now - lastDrawTime >= DELAY) {
                    double x = e.getX();
                    double y = e.getY();
                    double distance = Math.hypot(x - lastX, y - lastY);
                    if (distance > DISTANCE) {
                        gc.lineTo(x, y);
                        gc.stroke();
                        gc.beginPath();
                        gc.moveTo(x, y);
                        lastX = x;
                        lastY = y;
                    }
                    lastDrawTime = now;
                }
            }
        });
    }

    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int)(color.getRed()*255),
                (int)(color.getGreen()*255),
                (int)(color.getBlue()*255));
    }

    private void buttonManager(Button selectedButton) {
        deselectAllTextFields();
        if (selectedButton != penBtn && popup != null && popup.isShowing()) popup.hide();
    }

    private void deselectAllTextFields() {
        for (customTextField tf : textFields) {
            tf.deselectTextField();
        }
    }
}