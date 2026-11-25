package org.example.canvasproject.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Сравнение JSON без зависимости от порядка ключей и от того, как драйвер
 * Mongo разобрал число (Integer, Long, Double). Иначе одна и та же доска
 * выглядела бы «изменившейся» после переноса.
 */
final class JsonCanonical {

    private JsonCanonical() {
    }

    static boolean same(JSONObject first, JSONObject second) {
        return Objects.equals(normalize(first), normalize(second));
    }

    static Object normalize(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject object) {
            Map<String, Object> sorted = new TreeMap<>();
            for (String key : object.keySet()) sorted.put(key, normalize(object.get(key)));
            return sorted;
        }
        if (value instanceof JSONArray array) {
            List<Object> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) list.add(normalize(array.get(i)));
            return list;
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (!Double.isFinite(asDouble)) return number.toString();
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return value;
    }
}
