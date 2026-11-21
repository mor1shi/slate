package org.example.canvasproject;

import java.util.HashMap;
import java.util.Map;

/**
 * Хранит размеры канвасов в памяти сессии
 */
public class CanvasSizeCache {

    private static final Map<String, int[]> cache = new HashMap<>();

    public static void put(String canvasId, int width, int height) {
        if (canvasId == null || canvasId.isBlank()) return;
        cache.put(canvasId, new int[]{width, height});
    }

    public static int[] get(String canvasId) {
        return cache.getOrDefault(canvasId, new int[]{1920, 1080});
    }

    public static int getWidth(String canvasId)  { return get(canvasId)[0]; }
    public static int getHeight(String canvasId) { return get(canvasId)[1]; }

    public static boolean has(String canvasId) {
        return cache.containsKey(canvasId);
    }
}
