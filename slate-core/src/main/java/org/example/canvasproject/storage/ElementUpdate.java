package org.example.canvasproject.storage;

import org.json.JSONObject;

/** Один элемент в пакетном обновлении UPDATE_ELEMENTS. */
public record ElementUpdate(String elementId, JSONObject data) {
    public ElementUpdate {
        if (data == null) data = new JSONObject();
    }
}
