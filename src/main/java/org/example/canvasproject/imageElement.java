package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.UUID;

/**
 * Фотография как элемент доски.
 *
 * БАГ-ФИХ: добавлено поле cachedImageData для хранения base64/URL исходного
 * изображения. Это нужно потому что JavaFX Image, созданный из InputStream,
 * возвращает null из getUrl() — и повторная сериализация теряла данные,
 * из-за чего фото не сохранялись при перезагрузке канваса.
 */
public class imageElement extends ImageView implements Selectable {

    private final String id = UUID.randomUUID().toString();

    /**
     * Кешированные данные изображения (base64 data URL или http URL).
     * Устанавливается при десериализации и при первом кодировании файла.
     */
    private String cachedImageData;

    public imageElement(Image image, double x, double y, double width, double height) {
        super(image);
        setLayoutX(x);
        setLayoutY(y);
        setFitWidth(width);
        setFitHeight(height);
        setPreserveRatio(false);
        setCursor(Cursor.HAND);
    }

    /** Возвращает кешированные данные для повторной сериализации. */
    public String getCachedImageData() { return cachedImageData; }

    /** Устанавливает кешированные данные (вызывается при десериализации). */
    public void setCachedImageData(String data) { this.cachedImageData = data; }

    @Override public String getElementId() { return id; }
    @Override public String getType() { return "image"; }
    @Override public Node getNode() { return this; }

    @Override
    public Bounds getSelectBounds() {
        return new BoundingBox(getLayoutX(), getLayoutY(), getFitWidth(), getFitHeight());
    }

    @Override
    public void move(double dx, double dy) {
        setLayoutX(getLayoutX() + dx);
        setLayoutY(getLayoutY() + dy);
    }

    @Override
    public void resize(String anchor, double newWidth, double newHeight) {
        double oldX = getLayoutX();
        double oldY = getLayoutY();
        double oldW = getFitWidth();
        double oldH = getFitHeight();

        double w = Math.max(newWidth, 30);
        double h = Math.max(newHeight, 30);

        double newX = oldX;
        double newY = oldY;

        switch (anchor) {
            case "NW":
                newX = oldX + oldW - w;
                newY = oldY + oldH - h;
                break;
            case "NE":
                newX = oldX;
                newY = oldY + oldH - h;
                break;
            case "SW":
                newX = oldX + oldW - w;
                newY = oldY;
                break;
            case "SE":
            default:
                newX = oldX;
                newY = oldY;
                break;
        }

        setLayoutX(newX);
        setLayoutY(newY);
        setFitWidth(w);
        setFitHeight(h);
    }

    @Override public boolean isResizable() { return true; }

    private static class State implements ElementMemento {
        final double x, y, w, h;
        final String cachedImageData;
        State(double x, double y, double w, double h, String cached) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.cachedImageData = cached;
        }
    }

    @Override
    public ElementMemento save() {
        return new State(getLayoutX(), getLayoutY(), getFitWidth(), getFitHeight(), cachedImageData);
    }

    @Override
    public void restore(ElementMemento memento) {
        State s = (State) memento;
        setLayoutX(s.x);
        setLayoutY(s.y);
        setFitWidth(s.w);
        setFitHeight(s.h);
        // Восстанавливаем кеш, чтобы сериализация после undo работала корректно
        if (s.cachedImageData != null) this.cachedImageData = s.cachedImageData;
    }
}
