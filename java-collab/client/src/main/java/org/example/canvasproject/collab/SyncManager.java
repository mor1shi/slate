package org.example.canvasproject.collab;

import javafx.geometry.Point2D;
import javafx.scene.paint.Color;
import org.example.canvasproject.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Мост между BoardModel и сервером совместной работы.
 *
 * Главный контроллер вызывает методы SyncManager при каждом изменении
 * (добавление, удаление, обновление элемента).
 *
 * SyncManager слушает входящие сообщения от сервера и применяет их
 * к локальному BoardModel.
 *
 * Подключение:
 *   syncManager = new SyncManager(model, session);
 *   session.getClient().setMessageHandler(syncManager::onServerMessage);
 */
public class SyncManager {

    private static final Logger log = Logger.getLogger(SyncManager.class.getName());

    private final BoardModel model;
    private final SessionManager session;
    private boolean syncEnabled = false;

    // Колбэк для создания cursor-оверлея другого пользователя
    private CursorUpdateCallback cursorCallback;

    public interface CursorUpdateCallback {
        void onCursorUpdate(String userId, String username, double x, double y);
    }

    public SyncManager(BoardModel model, SessionManager session) {
        this.model   = model;
        this.session = session;
    }

    public void setCursorCallback(CursorUpdateCallback cb) {
        this.cursorCallback = cb;
    }

    public void setSyncEnabled(boolean enabled) {
        this.syncEnabled = enabled;
    }

    public boolean isSyncEnabled() {
        return syncEnabled && session.isConnected() && session.isLoggedIn();
    }

    // ─────────────────── LOCAL → SERVER ───────────────────

    /** Вызывается из контроллера после добавления элемента на канвас */
    public void notifyAdded(Selectable el) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;

        JSONObject data = serializeElement(el);
        session.getClient().addElement(canvasId, el.getElementId(), el.getType(), data);
    }

    /** Вызывается из контроллера после удаления элемента */
    public void notifyDeleted(Selectable el) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;

        session.getClient().deleteElement(canvasId, el.getElementId());
    }

    /** Вызывается из контроллера после изменения (move/resize/edit) */
    public void notifyUpdated(Selectable el) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;

        JSONObject data = serializeElement(el);
        session.getClient().updateElement(canvasId, el.getElementId(), data);
    }

    /** Движение курсора (вызывается при mouseMoved) */
    public void notifyCursorMove(double x, double y) {
        if (!isSyncEnabled()) return;
        String canvasId = session.getActiveCanvasId();
        if (canvasId == null) return;

        session.getClient().sendCursorMove(canvasId, x, y);
    }

    // ─────────────────── SERVER → LOCAL ───────────────────

    /** Обрабатывает сообщения от сервера. Подключается через setMessageHandler. */
    public void onServerMessage(JSONObject msg) {
        String type = msg.optString("type", "");
        switch (type) {
            case "ELEMENT_ADDED"   -> applyRemoteAdd(msg);
            case "ELEMENT_DELETED" -> applyRemoteDelete(msg);
            case "ELEMENT_UPDATED" -> applyRemoteUpdate(msg);
            case "CURSOR_MOVED"    -> applyRemoteCursor(msg);
            // Остальные типы (LOGIN_OK, CANVAS_LIST, и т.д.) обрабатываются в контроллерах
        }
    }

    private void applyRemoteAdd(JSONObject msg) {
        String elementId   = msg.optString("elementId");
        String elementType = msg.optString("elementType");
        JSONObject data    = msg.optJSONObject("data");

        // Избегаем дублирования: если уже есть у нас — пропускаем
        for (Selectable el : model.elements()) {
            if (el.getElementId().equals(elementId)) return;
        }

        Selectable el = deserializeElement(elementId, elementType, data);
        if (el != null) {
            model.add(el);
        }
    }

    private void applyRemoteDelete(JSONObject msg) {
        String elementId = msg.optString("elementId");
        model.elements().stream()
            .filter(el -> el.getElementId().equals(elementId))
            .findFirst()
            .ifPresent(model::remove);
    }

    private void applyRemoteUpdate(JSONObject msg) {
        String elementId = msg.optString("elementId");
        JSONObject data  = msg.optJSONObject("data");
        model.elements().stream()
            .filter(el -> el.getElementId().equals(elementId))
            .findFirst()
            .ifPresent(el -> applyDataToElement(el, data));
    }

    private void applyRemoteCursor(JSONObject msg) {
        if (cursorCallback == null) return;
        String userId   = msg.optString("userId");
        String username = msg.optString("username");
        double x = msg.optDouble("x", 0);
        double y = msg.optDouble("y", 0);
        cursorCallback.onCursorUpdate(userId, username, x, y);
    }

    // ─────────────────── ЗАГРУЗКА КАНВАСА ───────────────────

    /**
     * Применяет список элементов с сервера при входе на канвас.
     * Вызывается после получения JOINED_CANVAS.
     */
    public void loadCanvasElements(JSONArray elements) {
        // Очищаем текущие элементы
        List<Selectable> current = new java.util.ArrayList<>(model.elements());
        current.forEach(model::remove);

        for (int i = 0; i < elements.length(); i++) {
            JSONObject elem = elements.getJSONObject(i);
            String elementId   = elem.optString("elementId");
            String elementType = elem.optString("elementType");
            JSONObject data    = elem.optJSONObject("data");

            Selectable el = deserializeElement(elementId, elementType, data);
            if (el != null) model.add(el);
        }
    }

    // ─────────────────── СЕРИАЛИЗАЦИЯ ───────────────────

    private JSONObject serializeElement(Selectable el) {
        JSONObject data = new JSONObject();
        switch (el.getType()) {
            case "line" -> serializeLine(el, data);
            case "text" -> serializeText(el, data);
            case "figure" -> serializeFigure(el, data);
            case "note" -> serializeNote(el, data);
            case "image" -> serializeImage(el, data);
        }
        return data;
    }

    private void serializeLine(Selectable el, JSONObject data) {
        if (el instanceof lineElement line) {
            data.put("layoutX", line.getLayoutX());
            data.put("layoutY", line.getLayoutY());
            data.put("strokeWidth", line.getStrokeWidth());
            Color c = (Color) line.getStroke();
            data.put("color", colorToHex(c));
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
            data.put("width", note.getPrefWidth());
            data.put("text",  note.getText());
            data.put("color", colorToHex(note.getNoteColor()));
        }
    }

    private void serializeImage(Selectable el, JSONObject data) {
        if (el instanceof imageElement img) {
            data.put("x",      img.getLayoutX());
            data.put("y",      img.getLayoutY());
            data.put("width",  img.getFitWidth());
            data.put("height", img.getFitHeight());
            // Для изображений храним URL если доступен
            if (img.getImage() != null && img.getImage().getUrl() != null) {
                data.put("url", img.getImage().getUrl());
            }
        }
    }

    private Selectable deserializeElement(String elementId, String type, JSONObject data) {
        if (data == null) return null;
        try {
            return switch (type) {
                case "line"   -> deserializeLine(elementId, data);
                case "text"   -> deserializeText(elementId, data);
                case "figure" -> deserializeFigure(elementId, data);
                case "note"   -> deserializeNote(elementId, data);
                default       -> null;
            };
        } catch (Exception e) {
            log.warning("Failed to deserialize element " + elementId + ": " + e.getMessage());
            return null;
        }
    }

    private lineElement deserializeLine(String id, JSONObject data) {
        Color color = hexToColor(data.optString("color", "#000000"));
        double width = data.optDouble("strokeWidth", 2.0);
        lineElement line = new lineElement(color, width);
        line.setId(id);
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
        tf.setId(id);
        return tf;
    }

    private figureElement deserializeFigure(String id, JSONObject data) {
        figureElement fig = new figureElement(
            data.optString("figureType", "rect"),
            data.optDouble("x", 0),
            data.optDouble("y", 0),
            data.optDouble("width", 100),
            data.optDouble("height", 80),
            hexToColor(data.optString("color", "#007AFF"))
        );
        fig.setId(id);
        return fig;
    }

    private stickyNote deserializeNote(String id, JSONObject data) {
        stickyNote note = new stickyNote(
            data.optDouble("x", 0),
            data.optDouble("y", 0),
            hexToColor(data.optString("color", "#FFD60A"))
        );
        note.setText(data.optString("text", ""));
        note.setId(id);
        return note;
    }

    private void applyDataToElement(Selectable el, JSONObject data) {
        if (data == null) return;
        if (el instanceof customTextField tf) {
            tf.setLayoutX(data.optDouble("x", tf.getLayoutX()));
            tf.setLayoutY(data.optDouble("y", tf.getLayoutY()));
            tf.setPrefWidth(data.optDouble("width", tf.getPrefWidth()));
            tf.setPrefHeight(data.optDouble("height", tf.getPrefHeight()));
            tf.setText(data.optString("text", tf.getText()));
        } else if (el instanceof figureElement fig) {
            fig.setX(data.optDouble("x", fig.getX()));
            fig.setY(data.optDouble("y", fig.getY()));
            fig.setWidth(data.optDouble("width", fig.getWidth()));
            fig.setHeight(data.optDouble("height", fig.getHeight()));
        } else if (el instanceof stickyNote note) {
            note.setLayoutX(data.optDouble("x", note.getLayoutX()));
            note.setLayoutY(data.optDouble("y", note.getLayoutY()));
            note.setText(data.optString("text", note.getText()));
        } else if (el instanceof lineElement line) {
            line.setLayoutX(data.optDouble("layoutX", line.getLayoutX()));
            line.setLayoutY(data.optDouble("layoutY", line.getLayoutY()));
        }
    }

    // ─────────────────── УТИЛИТЫ ───────────────────

    private String colorToHex(Color color) {
        if (color == null) return "#000000";
        return String.format("#%02X%02X%02X",
            (int)(color.getRed()*255),
            (int)(color.getGreen()*255),
            (int)(color.getBlue()*255));
    }

    private Color hexToColor(String hex) {
        try { return Color.web(hex); }
        catch (Exception e) { return Color.BLACK; }
    }
}
