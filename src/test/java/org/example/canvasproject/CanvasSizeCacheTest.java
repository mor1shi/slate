package org.example.canvasproject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasSizeCacheTest {

    @Test
    void unknownCanvasFallsBackToDefaultSheetSize() {
        assertArrayEquals(new int[]{1920, 1080}, CanvasSizeCache.get("unknown-canvas"));
        assertFalse(CanvasSizeCache.has("unknown-canvas"));
    }

    @Test
    void storedSizeIsReturnedAndCanBeOverwritten() {
        CanvasSizeCache.put("cache-test-1", 4000, 3000);
        assertTrue(CanvasSizeCache.has("cache-test-1"));
        assertEquals(4000, CanvasSizeCache.getWidth("cache-test-1"));
        assertEquals(3000, CanvasSizeCache.getHeight("cache-test-1"));

        CanvasSizeCache.put("cache-test-1", 800, 600);
        assertArrayEquals(new int[]{800, 600}, CanvasSizeCache.get("cache-test-1"));
    }

    @Test
    void blankCanvasIdIsIgnored() {
        CanvasSizeCache.put(null, 100, 100);
        CanvasSizeCache.put("  ", 100, 100);
        assertFalse(CanvasSizeCache.has(null));
        assertFalse(CanvasSizeCache.has("  "));
        assertArrayEquals(new int[]{1920, 1080}, CanvasSizeCache.get(null));
    }
}
