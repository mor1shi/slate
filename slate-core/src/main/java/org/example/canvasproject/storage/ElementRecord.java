package org.example.canvasproject.storage;

import org.json.JSONObject;

/** Элемент доски; data — тот же JSON, что клиент отправляет в теле протокола. */
public record ElementRecord(String elementId, String elementType, JSONObject data,
                            String addedBy, long updatedAt) {

    public ElementRecord {
        if (data == null) data = new JSONObject();
    }

    public ElementRecord withData(JSONObject newData) {
        return new ElementRecord(elementId, elementType, newData, addedBy,
                System.currentTimeMillis());
    }
}
