package org.example.canvasproject.storage;

import org.json.JSONObject;

import java.util.Base64;

/**
 * Переносит встроенные `data:image/...;base64,...` из документов доски в
 * {@link AssetStore}. В элементе остаётся только `assetId` (SHA-256) и MIME.
 * Старые доски читаются как раньше: пока в данных есть `imageData`, картинка
 * показывается, а при следующем сохранении переезжает в ассеты.
 */
public final class AssetMigration {

    /** Возвращает true, если данные элемента изменились. */
    public static boolean migrateElementData(JSONObject data, AssetStore store) {
        if (data == null || store == null) return false;
        String imageData = data.optString("imageData", "");
        if (imageData.isEmpty()) return false;
        if (!imageData.startsWith("data:image/")) return false;

        int comma = imageData.indexOf(',');
        if (comma < 0) return false;
        try {
            byte[] raw = Base64.getDecoder().decode(imageData.substring(comma + 1));
            ImageAssets.Prepared prepared = ImageAssets.prepare(raw);
            String hash = store.put(prepared.bytes());
            data.put("assetId", hash);
            data.put("mime", prepared.mimeType());
            data.remove("imageData");
            return true;
        } catch (RuntimeException e) {
            // Битая картинка не должна ломать открытие доски: оставляем как есть.
            return false;
        }
    }

    /** Мигрирует все элементы массива; возвращает число изменённых. */
    public static int migrateAll(org.json.JSONArray elements, AssetStore store) {
        if (elements == null) return 0;
        int changed = 0;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item == null) continue;
            JSONObject data = item.optJSONObject("data");
            if (migrateElementData(data, store)) changed++;
        }
        return changed;
    }

    private AssetMigration() {}
}
