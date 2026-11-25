package org.example.canvasproject.transport;

import org.example.canvasproject.storage.AssetStore;
import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.ElementUpdate;
import org.example.canvasproject.storage.FileAssetStore;
import org.example.canvasproject.storage.Storage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Серверный слой команд без сокета: solo-доска проходит те же проверки
 * прав и те же сообщения, что и при работе через WebSocket. Ответы
 * возвращаются отправителю синхронно; широковещательных событий нет,
 * потому что участник один.
 */
public final class LocalServer {

    private static final int MIN_WIDTH = 400;
    private static final int MAX_WIDTH = 4000;
    private static final int MIN_HEIGHT = 300;
    private static final int MAX_HEIGHT = 4000;

    private final Storage storage;
    private final String userId;
    private final String username;
    private final String defaultRole;

    public LocalServer(Storage storage, String userId, String username, String role) {
        this.storage = storage;
        this.userId = userId == null || userId.isBlank() ? BoardRecord.LOCAL_OWNER : userId;
        this.username = username == null || username.isBlank() ? "Локальный" : username;
        this.defaultRole = role == null || role.isBlank() ? "owner" : role;
    }

    public JSONObject handle(JSONObject msg) {
        if (msg == null) return error("Пустое сообщение");
        String type = msg.optString("type", "");
        try {
            return switch (type) {
                case "AUTH_DEVICE", "LOGIN", "REGISTER" -> auth();
                case "LIST_CANVASES" -> listCanvases();
                case "CREATE_CANVAS" -> createCanvas(msg);
                case "JOIN_CANVAS" -> joinCanvas(msg);
                case "RENAME_CANVAS" -> renameCanvas(msg);
                case "RESIZE_CANVAS" -> resizeCanvas(msg);
                case "DELETE_CANVAS" -> deleteCanvas(msg);
                case "ADD_ELEMENT" -> addElement(msg);
                case "UPDATE_ELEMENT" -> updateElement(msg);
                case "DELETE_ELEMENT" -> deleteElement(msg);
                case "UPDATE_ELEMENTS" -> updateElements(msg);
                case "DELETE_ELEMENTS" -> deleteElements(msg);
                case "ASSET_PUT" -> putAsset(msg);
                case "ASSET_GET" -> getAsset(msg);
                case "CURSOR_MOVE" -> new JSONObject().put("type", "NOOP");
                case "LEAVE_CANVAS" -> new JSONObject().put("type", "LEFT_CANVAS");
                default -> error("Неизвестная команда: " + type);
            };
        } catch (RuntimeException e) {
            return error("Ошибка локального сервера: " + e.getMessage());
        }
    }

    private JSONObject auth() {
        return new JSONObject()
                .put("type", "AUTH_OK")
                .put("token", "local")
                .put("userId", userId)
                .put("username", username)
                .put("color", "")
                .put("avatar", "");
    }

    private JSONObject listCanvases() {
        JSONArray arr = new JSONArray();
        for (BoardRecord board : storage.boards().listForUser(userId)) {
            arr.put(canvasJson(board));
        }
        return new JSONObject().put("type", "CANVAS_LIST").put("canvases", arr);
    }

    private JSONObject createCanvas(JSONObject msg) {
        if (!canCreateBoards()) return error("Недостаточно прав для создания доски");
        String name = cleanName(msg.optString("name", "Без названия"));
        int width = clamp(msg.optInt("canvasWidth", msg.optInt("width", 1920)),
                MIN_WIDTH, MAX_WIDTH);
        int height = clamp(msg.optInt("canvasHeight", msg.optInt("height", 1080)),
                MIN_HEIGHT, MAX_HEIGHT);
        BoardRecord board = storage.boards().create(name, userId, width, height);
        storage.boards().addMember(board.id(), userId, "owner");
        return new JSONObject().put("type", "CANVAS_CREATED").put("canvas", canvasJson(board));
    }

    private JSONObject joinCanvas(JSONObject msg) {
        BoardRecord board = storage.boards().find(msg.optString("canvasId")).orElse(null);
        String role = board == null ? null : board.roleOf(userId);
        if (board == null || role == null) return error("Доска не найдена");

        JSONArray elements = new JSONArray();
        for (ElementRecord record : storage.elements().list(board.id())) {
            elements.put(elementJson(record));
        }
        return new JSONObject()
                .put("type", "JOINED_CANVAS")
                .put("canvasId", board.id())
                .put("canvasName", board.name())
                .put("canvasWidth", board.width())
                .put("canvasHeight", board.height())
                .put("ownerId", board.ownerId())
                .put("role", role)
                .put("elements", elements);
    }

    private JSONObject renameCanvas(JSONObject msg) {
        BoardRecord board = storage.boards().find(msg.optString("canvasId")).orElse(null);
        if (board == null) return error("Доска не найдена");
        if (!board.isOwner(userId)) return error("Только владелец может переименовать доску");
        String name = cleanName(msg.optString("name"));
        storage.boards().rename(board.id(), name);
        return new JSONObject().put("type", "CANVAS_RENAMED")
                .put("canvasId", board.id()).put("name", name);
    }

    private JSONObject resizeCanvas(JSONObject msg) {
        BoardRecord board = storage.boards().find(msg.optString("canvasId")).orElse(null);
        if (board == null) return error("Доска не найдена");
        if (!board.canEdit(userId)) return error("Только редактор может изменять доску");
        int width = msg.optInt("width", msg.optInt("canvasWidth", -1));
        int height = msg.optInt("height", msg.optInt("canvasHeight", -1));
        if (width < MIN_WIDTH || width > MAX_WIDTH
                || height < MIN_HEIGHT || height > MAX_HEIGHT) {
            return error("Размер холста: ширина 400–4000, высота 300–4000");
        }
        storage.boards().resize(board.id(), width, height);
        return new JSONObject().put("type", "CANVAS_RESIZED")
                .put("canvasId", board.id())
                .put("canvasWidth", width).put("canvasHeight", height);
    }

    private JSONObject deleteCanvas(JSONObject msg) {
        BoardRecord board = storage.boards().find(msg.optString("canvasId")).orElse(null);
        if (board == null) return error("Доска не найдена");
        if (!board.isOwner(userId)) return error("Только владелец может удалить доску");
        storage.boards().delete(board.id());
        return new JSONObject().put("type", "CANVAS_DELETED").put("canvasId", board.id());
    }

    private JSONObject addElement(JSONObject msg) {
        String canvasId = msg.optString("canvasId");
        BoardRecord board = storage.boards().find(canvasId).orElse(null);
        if (board == null || !board.canEdit(userId)) {
            return error("Только редактор может изменять доску");
        }
        String elementId = msg.optString("elementId", "");
        String elementType = msg.optString("elementType", "");
        JSONObject data = msg.optJSONObject("data");
        if (elementId.isBlank() || elementId.length() > 128 || elementType.length() > 32) {
            return error("Некорректный элемент");
        }
        storage.elements().add(canvasId, new ElementRecord(elementId, elementType,
                data == null ? new JSONObject() : data, userId, System.currentTimeMillis()));
        return new JSONObject().put("type", "ELEMENT_ADDED")
                .put("canvasId", canvasId).put("elementId", elementId)
                .put("elementType", elementType)
                .put("data", data == null ? new JSONObject() : data);
    }

    private JSONObject updateElement(JSONObject msg) {
        String canvasId = msg.optString("canvasId");
        BoardRecord board = storage.boards().find(canvasId).orElse(null);
        if (board == null || !board.canEdit(userId)) {
            return error("Только редактор может изменять доску");
        }
        String elementId = msg.optString("elementId", "");
        JSONObject data = msg.optJSONObject("data");
        if (elementId.isBlank() || data == null) return error("Некорректное обновление элемента");
        if (!storage.elements().update(canvasId, elementId, data)) {
            return error("Элемент не найден");
        }
        return new JSONObject().put("type", "ELEMENT_UPDATED")
                .put("canvasId", canvasId).put("elementId", elementId).put("data", data);
    }

    private JSONObject deleteElement(JSONObject msg) {
        String canvasId = msg.optString("canvasId");
        BoardRecord board = storage.boards().find(canvasId).orElse(null);
        if (board == null || !board.canEdit(userId)) {
            return error("Только редактор может изменять доску");
        }
        String elementId = msg.optString("elementId", "");
        if (elementId.isBlank()) return error("Некорректный элемент");
        storage.elements().delete(canvasId, elementId);
        return new JSONObject().put("type", "ELEMENT_DELETED")
                .put("canvasId", canvasId).put("elementId", elementId);
    }

    private JSONObject updateElements(JSONObject msg) {
        String canvasId = msg.optString("canvasId");
        BoardRecord board = storage.boards().find(canvasId).orElse(null);
        if (board == null || !board.canEdit(userId)) {
            return error("Только редактор может изменять доску");
        }
        JSONArray updates = msg.optJSONArray("updates");
        if (updates == null || updates.isEmpty()) return error("Пустой пакет обновлений");

        List<ElementUpdate> parsed = new ArrayList<>();
        for (int i = 0; i < updates.length(); i++) {
            JSONObject item = updates.optJSONObject(i);
            String elementId = item == null ? "" : item.optString("elementId", "");
            JSONObject data = item == null ? null : item.optJSONObject("data");
            if (elementId.isBlank() || data == null) {
                return error("Пакет отклонён: у элемента " + i + " нет id или data");
            }
            parsed.add(new ElementUpdate(elementId, data));
        }
        storage.elements().updateMany(canvasId, parsed);
        return new JSONObject().put("type", "ELEMENTS_UPDATED")
                .put("canvasId", canvasId).put("count", parsed.size()).put("updates", updates);
    }

    private JSONObject deleteElements(JSONObject msg) {
        String canvasId = msg.optString("canvasId");
        BoardRecord board = storage.boards().find(canvasId).orElse(null);
        if (board == null || !board.canEdit(userId)) {
            return error("Только редактор может изменять доску");
        }
        JSONArray ids = msg.optJSONArray("elementIds");
        if (ids == null || ids.isEmpty()) return error("Пустой список элементов");
        List<String> parsed = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, "");
            if (id.isBlank()) return error("Пакет отклонён: пустой id элемента");
            parsed.add(id);
        }
        int changed = storage.elements().deleteMany(canvasId, parsed);
        return new JSONObject().put("type", "ELEMENTS_DELETED")
                .put("canvasId", canvasId).put("count", changed).put("elementIds", ids);
    }

    private JSONObject putAsset(JSONObject msg) {
        String assetId = msg.optString("assetId", "");
        String payload = msg.optString("data", "");
        if (!assetId.matches("[0-9a-f]{64}") || payload.isEmpty()) {
            return error("Некорректный ассет");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (!FileAssetStore.hash(bytes).equals(assetId)) {
                return error("Хеш ассета не совпадает");
            }
            storage.assets().put(bytes);
            return new JSONObject().put("type", "ASSET_STORED").put("assetId", assetId);
        } catch (IllegalArgumentException e) {
            return error("Ассет отклонён: " + e.getMessage());
        }
    }

    private JSONObject getAsset(JSONObject msg) {
        String assetId = msg.optString("assetId", "");
        if (!assetId.matches("[0-9a-f]{64}")) return error("Некорректный ассет");
        Optional<byte[]> bytes = storage.assets().get(assetId);
        if (bytes.isEmpty()) return error("Файл не найден");
        return new JSONObject().put("type", "ASSET_DATA").put("assetId", assetId)
                .put("data", Base64.getEncoder().encodeToString(bytes.get()));
    }

    private boolean canCreateBoards() {
        return "owner".equals(defaultRole) || "editor".equals(defaultRole);
    }

    private JSONObject canvasJson(BoardRecord board) {
        return new JSONObject()
                .put("id", board.id())
                .put("name", board.name())
                .put("width", board.width())
                .put("height", board.height())
                .put("updatedAt", board.updatedAt());
    }

    private JSONObject elementJson(ElementRecord record) {
        return new JSONObject()
                .put("elementId", record.elementId())
                .put("elementType", record.elementType())
                .put("data", record.data())
                .put("addedBy", record.addedBy())
                .put("updatedAt", record.updatedAt());
    }

    private static String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) value = "Без названия";
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static JSONObject error(String message) {
        return new JSONObject().put("type", "ERROR").put("message", message);
    }
}
