package org.example.canvasproject.collab;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import org.example.canvasproject.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.net.URI;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.logging.Logger;

public class SyncManager {

    private static final Logger log = Logger.getLogger(SyncManager.class.getName());

    private final BoardModel model;
    private final SessionManager session;
    private boolean syncEnabled = false;

    private CursorUpdateCallback cursorCallback;
    private java.util.function.Consumer<Selectable> onElementAdded;

    private final IdentityHashMap<Selectable, String> serverIdMap = new IdentityHashMap<>();

    public interface CursorUpdateCallback {
        void onCursorUpdate(String userId, String username, double x, double y, String color);
    }

    public SyncManager(BoardModel model, SessionManager session) {
        this.model   = model;
        this.session = session;
    }

    public void setCursorCallback(CursorUpdateCallback cb) { this.cursorCallback = cb; }
    public void setOnElementAdded(java.util.function.Consumer<Selectable> cb) { this.onElementAdded = cb; }
    public void setSyncEnabled(boolean enabled) { this.syncEnabled = enabled; }
    public boolean isSyncEnabled() {
        return syncEnabled && session.isConnected() && session.isLoggedIn();
    }

    private String resolveId(Selectable el) {
        return serverIdMap.getOrDefault(el, el.getElementId());
    }


    public void notifyAdded(Selectable el) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;
        session.getClient().addElement(canvasId, resolveId(el), el.getType(), serializeElement(el));
    }

    public void notifyDeleted(Selectable el) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;
        session.getClient().deleteElement(canvasId, resolveId(el));
        serverIdMap.remove(el);
    }

    public void notifyUpdated(Selectable el) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;
        session.getClient().updateElement(canvasId, resolveId(el), serializeElement(el));
    }

    public void notifyCursorMove(double x, double y) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;
        session.getClient().sendCursorMove(canvasId, x, y);
    }

    /**
     * БАГ-ФИХ: Прикрепляет слушатели изменения текста к элементу, чтобы
     * изменения синхронизировались в реальном времени, без необходимости
     * заканчивать редактирование.
     *
     * Вызывать для каждого нового элемента — и локального, и удалённого.
     */
    public void watchTextChanges(Selectable el) {
        if (el instanceof customTextField tf) {
            tf.textProperty().addListener((obs, oldVal, newVal) -> {
                if (tf.isEditMode()) {
                    notifyUpdated(tf);
                }
            });
        } else if (el instanceof stickyNote note) {
            note.attachSyncListener(() -> notifyUpdated(note));
        }
    }


    public void onServerMessage(JSONObject msg) {
        String type = msg.optString("type", "");
        switch (type) {
            case "ELEMENT_ADDED"   -> applyRemoteAdd(msg);
            case "ELEMENT_DELETED" -> applyRemoteDelete(msg);
            case "ELEMENT_UPDATED" -> applyRemoteUpdate(msg);
            case "CURSOR_MOVED"    -> applyRemoteCursor(msg);
        }
    }

    private void applyRemoteAdd(JSONObject msg) {
        String elementId   = msg.optString("elementId");
        String elementType = msg.optString("elementType");
        JSONObject data    = msg.optJSONObject("data");

        for (Selectable el : model.elements()) {
            if (resolveId(el).equals(elementId)) return;
        }

        Selectable el = deserializeElement(elementId, elementType, data);
        if (el == null) return;

        Platform.runLater(() -> {
            model.add(el);
            if (onElementAdded != null) onElementAdded.accept(el);
        });
    }

    private void applyRemoteDelete(JSONObject msg) {
        String elementId = msg.optString("elementId");
        Platform.runLater(() ->
            model.elements().stream()
                .filter(el -> resolveId(el).equals(elementId))
                .findFirst()
                .ifPresent(el -> {
                    model.remove(el);
                    serverIdMap.remove(el);
                })
        );
    }

    private void applyRemoteUpdate(JSONObject msg) {
        String elementId = msg.optString("elementId");
        JSONObject data  = msg.optJSONObject("data");
        Platform.runLater(() ->
            model.elements().stream()
                .filter(el -> resolveId(el).equals(elementId))
                .findFirst()
                .ifPresent(el -> applyDataToElement(el, data))
        );
    }

    private void applyRemoteCursor(JSONObject msg) {
        if (cursorCallback == null) return;
        String userId   = msg.optString("userId");
        String username = msg.optString("username");
        String color    = msg.optString("color", "");
        double x = msg.optDouble("x", 0);
        double y = msg.optDouble("y", 0);
        Platform.runLater(() -> cursorCallback.onCursorUpdate(userId, username, x, y, color));
    }


    public void loadCanvasElements(JSONArray elements) {
        serverIdMap.clear();
        List<Selectable> current = new java.util.ArrayList<>(model.elements());
        Platform.runLater(() -> current.forEach(model::remove));

        for (int i = 0; i < elements.length(); i++) {
            JSONObject elem = elements.getJSONObject(i);
            String elementId   = elem.optString("elementId");
            String elementType = elem.optString("elementType");
            JSONObject data    = elem.optJSONObject("data");

            Selectable el = deserializeElement(elementId, elementType, data);
            if (el == null) continue;

            final Selectable fEl = el;
            Platform.runLater(() -> {
                model.add(fEl);
                if (onElementAdded != null) onElementAdded.accept(fEl);
            });
        }
    }


    private JSONObject serializeElement(Selectable el) {
        JSONObject data = new JSONObject();
        switch (el.getType()) {
            case "line"   -> serializeLine(el, data);
            case "text"   -> serializeText(el, data);
            case "figure" -> serializeFigure(el, data);
            case "note"   -> serializeNote(el, data);
            case "image"  -> serializeImage(el, data);
        }
        return data;
    }

    private void serializeLine(Selectable el, JSONObject data) {
        if (el instanceof lineElement line) {
            data.put("layoutX",     line.getLayoutX());
            data.put("layoutY",     line.getLayoutY());
            data.put("strokeWidth", line.getStrokeWidth());

            Color c = (Color) line.getStroke();
            data.put("color", colorToHex(c));
            // БАГ-ФИХ: сохраняем прозрачность из alpha-канала цвета обводки.
            // colorToHex теряет alpha, поэтому сохраняем отдельно.
            data.put("opacity", c != null ? c.getOpacity() : 1.0);

            // БАГ-ФИХ: сохраняем все точки, чтобы ресайз синхронизировался.
            JSONArray pts = new JSONArray();
            for (Double d : line.getPoints()) pts.put(d);
            data.put("points", pts);
        }
    }

    private void serializeText(Selectable el, JSONObject data) {
        if (el instanceof customTextField tf) {
            data.put("x",      tf.getLayoutX());
            data.put("y",      tf.getLayoutY());
            data.put("width",  tf.getPrefWidth());
            data.put("height", tf.getPrefHeight());
            data.put("text",   tf.getText());
            data.put("fontSize",  tf.getFontSize());
            data.put("fontColor", colorToHex(tf.getFontColor()));
        }
    }

    private void serializeFigure(Selectable el, JSONObject data) {
        if (el instanceof figureElement fig) {
            data.put("x",          fig.getX());
            data.put("y",          fig.getY());
            data.put("width",      fig.getWidth());
            data.put("height",     fig.getHeight());
            data.put("figureType", fig.getFigureType());
            data.put("color",      colorToHex(fig.getFillColor()));
        }
    }

    private void serializeNote(Selectable el, JSONObject data) {
        if (el instanceof stickyNote note) {
            data.put("x",     note.getLayoutX());
            data.put("y",     note.getLayoutY());
            // БАГ-ФИХ: ширина И высота — чтобы ресайз синхронизировался
            data.put("width",  note.getPrefWidth());
            data.put("height", note.getPrefHeight());
            data.put("text",   note.getText());
            // БАГ-ФИХ: сохраняем заголовок заметки
            data.put("title",  note.getTitle());
            data.put("color",  colorToHex(note.getNoteColor()));
            data.put("fontSize", note.getFontSize());
        }
    }

    private void serializeImage(Selectable el, JSONObject data) {
        if (!(el instanceof imageElement img)) return;

        data.put("x",      img.getLayoutX());
        data.put("y",      img.getLayoutY());
        data.put("width",  img.getFitWidth());
        data.put("height", img.getFitHeight());

        // БАГ-ФИХ: если изображение было создано из base64 (url == null),
        // image.getUrl() вернёт null и данные потеряются.
        // Используем кешированные данные из imageElement.
        String cached = img.getCachedImageData();
        if (cached != null && !cached.isEmpty()) {
            data.put("imageData", cached);
            return;
        }

        if (img.getImage() == null) return;

        String url = img.getImage().getUrl();
        if (url == null) return;

        if (url.startsWith("data:")) {
            data.put("imageData", url);
        } else if (url.startsWith("file:")) {
            try {
                File file = new File(new URI(url));
                byte[] bytes = Files.readAllBytes(file.toPath());
                String ext  = url.substring(url.lastIndexOf('.') + 1).toLowerCase();
                String mime = switch (ext) {
                    case "png"  -> "image/png";
                    case "gif"  -> "image/gif";
                    case "bmp"  -> "image/bmp";
                    case "webp" -> "image/webp";
                    default     -> "image/jpeg";
                };
                String b64 = Base64.getEncoder().encodeToString(bytes);
                String dataUrl = "data:" + mime + ";base64," + b64;
                data.put("imageData", dataUrl);
                // кешируем, чтобы последующие сериализации не читали файл повторно
                img.setCachedImageData(dataUrl);
            } catch (Exception e) {
                log.warning("Cannot encode image to base64: " + e.getMessage());
            }
        } else {
            data.put("imageData", url);
        }
    }

    private Selectable deserializeElement(String elementId, String type, JSONObject data) {
        if (data == null) return null;
        try {
            Selectable el = switch (type) {
                case "line"   -> deserializeLine(elementId, data);
                case "text"   -> deserializeText(elementId, data);
                case "figure" -> deserializeFigure(elementId, data);
                case "note"   -> deserializeNote(elementId, data);
                case "image"  -> deserializeImage(elementId, data);
                default       -> null;
            };
            if (el != null) serverIdMap.put(el, elementId);
            return el;
        } catch (Exception e) {
            log.warning("Failed to deserialize " + elementId + ": " + e.getMessage());
            return null;
        }
    }

    private lineElement deserializeLine(String id, JSONObject data) {
        // БАГ-ФИХ: восстанавливаем opacity из alpha-канала цвета
        Color color = hexToColor(data.optString("color", "#000000"));
        double opacity = data.optDouble("opacity", 1.0);
        if (opacity < 0.999) {
            color = Color.color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
        }

        double width = data.optDouble("strokeWidth", 2.0);
        lineElement line = new lineElement(color, width);
        line.setLayoutX(data.optDouble("layoutX", 0));
        line.setLayoutY(data.optDouble("layoutY", 0));

        JSONArray pts = data.optJSONArray("points");
        if (pts != null) {
            Double[] dpts = new Double[pts.length()];
            for (int i = 0; i < pts.length(); i++) dpts[i] = pts.getDouble(i);
            line.getPoints().setAll(dpts);
        }
        return line;
    }

    private customTextField deserializeText(String id, JSONObject data) {
        double x = data.optDouble("x", 0);
        double y = data.optDouble("y", 0);
        customTextField tf = new customTextField(x, y);
        tf.setPrefWidth(data.optDouble("width", 150));
        tf.setPrefHeight(data.optDouble("height", 40));
        tf.setText(data.optString("text", ""));
        tf.setFontSize(fontSizeOf(data, 14));
        tf.setFontColor(fontColorOf(data, Color.BLACK));
        return tf;
    }

    private figureElement deserializeFigure(String id, JSONObject data) {
        return new figureElement(
            data.optString("figureType", "rect"),
            data.optDouble("x", 0),
            data.optDouble("y", 0),
            data.optDouble("width", 100),
            data.optDouble("height", 80),
            hexToColor(data.optString("color", "#007AFF"))
        );
    }

    private stickyNote deserializeNote(String id, JSONObject data) {
        stickyNote note = new stickyNote(
            data.optDouble("x", 0),
            data.optDouble("y", 0),
            hexToColor(data.optString("color", "#FFD60A"))
        );
        note.setText(data.optString("text", ""));
        // БАГ-ФИХ: восстанавливаем заголовок и размер
        note.setTitle(data.optString("title", ""));
        note.setFontSize(fontSizeOf(data, 14));
        if (data.has("width"))  note.setPrefWidth(data.optDouble("width", 200));
        if (data.has("height")) note.setPrefHeight(data.optDouble("height", 160));
        return note;
    }

    private imageElement deserializeImage(String id, JSONObject data) {
        double x = data.optDouble("x", 0);
        double y = data.optDouble("y", 0);
        double w = data.optDouble("width", 200);
        double h = data.optDouble("height", 150);

        String imageData = data.optString("imageData", "");
        if (imageData.isEmpty()) {
            log.warning("deserializeImage: no imageData for element " + id);
            return null;
        }

        Image image;
        if (imageData.startsWith("data:")) {
            int commaIdx = imageData.indexOf(',');
            if (commaIdx < 0) return null;
            try {
                byte[] bytes = Base64.getDecoder().decode(imageData.substring(commaIdx + 1));
                image = new Image(new ByteArrayInputStream(bytes));
            } catch (Exception e) {
                log.warning("Cannot decode base64 image: " + e.getMessage());
                return null;
            }
        } else {
            try {
                image = new Image(imageData, true);
            } catch (Exception e) {
                log.warning("Cannot load image from URL: " + e.getMessage());
                return null;
            }
        }

        imageElement el = new imageElement(image, x, y, w, h);
        // БАГ-ФИХ: кешируем данные, чтобы повторная сериализация не падала
        el.setCachedImageData(imageData);
        return el;
    }

    private void applyDataToElement(Selectable el, JSONObject data) {
        if (data == null) return;

        if (el instanceof customTextField tf) {
            tf.setLayoutX(data.optDouble("x", tf.getLayoutX()));
            tf.setLayoutY(data.optDouble("y", tf.getLayoutY()));
            tf.setPrefWidth(data.optDouble("width", tf.getPrefWidth()));
            tf.setPrefHeight(data.optDouble("height", tf.getPrefHeight()));
            tf.setText(data.optString("text", tf.getText()));
            if (data.has("fontSize"))  tf.setFontSize(fontSizeOf(data, tf.getFontSize()));
            if (data.has("fontColor")) tf.setFontColor(fontColorOf(data, tf.getFontColor()));

        } else if (el instanceof figureElement fig) {
            fig.setX(data.optDouble("x", fig.getX()));
            fig.setY(data.optDouble("y", fig.getY()));
            fig.setWidth(data.optDouble("width", fig.getWidth()));
            fig.setHeight(data.optDouble("height", fig.getHeight()));
            // БАГ-ФИХ: применяем цвет фигуры при удалённом обновлении
            if (data.has("color")) {
                fig.setFillColor(hexToColor(data.optString("color")));
            }

        } else if (el instanceof stickyNote note) {
            note.setLayoutX(data.optDouble("x", note.getLayoutX()));
            note.setLayoutY(data.optDouble("y", note.getLayoutY()));
            note.setText(data.optString("text", note.getText()));
            // БАГ-ФИХ: применяем заголовок
            if (data.has("title")) note.setTitle(data.optString("title"));
            if (data.has("fontSize")) note.setFontSize(fontSizeOf(data, note.getFontSize()));
            // БАГ-ФИХ: применяем новый размер при ресайзе
            if (data.has("width"))  note.setPrefWidth(data.optDouble("width", note.getPrefWidth()));
            if (data.has("height")) note.setPrefHeight(data.optDouble("height", note.getPrefHeight()));

        } else if (el instanceof lineElement line) {
            line.setLayoutX(data.optDouble("layoutX", line.getLayoutX()));
            line.setLayoutY(data.optDouble("layoutY", line.getLayoutY()));

            // БАГ-ФИХ: применяем цвет с прозрачностью
            if (data.has("color")) {
                Color c = hexToColor(data.optString("color"));
                double opacity = data.optDouble("opacity", 1.0);
                if (opacity < 0.999) {
                    c = Color.color(c.getRed(), c.getGreen(), c.getBlue(), opacity);
                }
                line.setStroke(c);
            }
            if (data.has("strokeWidth")) {
                line.setStrokeWidth(data.optDouble("strokeWidth", line.getStrokeWidth()));
            }

            // БАГ-ФИХ: применяем точки, чтобы ресайз синхронизировался
            JSONArray pts = data.optJSONArray("points");
            if (pts != null && pts.length() > 0) {
                Double[] dpts = new Double[pts.length()];
                for (int i = 0; i < pts.length(); i++) dpts[i] = pts.getDouble(i);
                line.getPoints().setAll(dpts);
            }

        } else if (el instanceof imageElement img) {
            img.setLayoutX(data.optDouble("x", img.getLayoutX()));
            img.setLayoutY(data.optDouble("y", img.getLayoutY()));
            img.setFitWidth(data.optDouble("width", img.getFitWidth()));
            img.setFitHeight(data.optDouble("height", img.getFitHeight()));
            // Обновляем кеш, если пришли новые данные изображения
            String newData = data.optString("imageData", "");
            if (!newData.isEmpty()) img.setCachedImageData(newData);
        }
    }


    /** Общая часть стиля текста: используется и для текста, и для стикера. */
    public static JSONObject textStyleToJson(double fontSize, Color color) {
        JSONObject data = new JSONObject();
        data.put("fontSize", fontSize);
        data.put("fontColor", colorToHex(color));
        return data;
    }

    public static double fontSizeOf(JSONObject data, double fallback) {
        return data == null ? fallback : data.optDouble("fontSize", fallback);
    }

    public static Color fontColorOf(JSONObject data, Color fallback) {
        if (data == null || !data.has("fontColor")) return fallback;
        return hexToColor(data.optString("fontColor", colorToHex(fallback)));
    }

    private static String colorToHex(Color color) {
        if (color == null) return "#000000";
        return String.format("#%02X%02X%02X",
            (int)(color.getRed()   * 255),
            (int)(color.getGreen() * 255),
            (int)(color.getBlue()  * 255));
    }

    private static Color hexToColor(String hex) {
        try { return Color.web(hex); }
        catch (Exception e) { return Color.BLACK; }
    }
}
