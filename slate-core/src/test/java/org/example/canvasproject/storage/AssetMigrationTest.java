package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Красный до реализации: base64-картинки должны переезжать в AssetStore. */
class AssetMigrationTest {

    @TempDir
    Path temp;

    @Test
    void inlineImageMovesToStoreAndLeavesOnlyReference() throws Exception {
        FileAssetStore store = new FileAssetStore(temp.resolve("assets"));
        byte[] png = pngBytes();
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        JSONObject data = new JSONObject()
                .put("x", 10).put("y", 20)
                .put("width", 100).put("height", 80)
                .put("imageData", dataUrl);

        assertTrue(AssetMigration.migrateElementData(data, store));
        assertFalse(data.has("imageData"), "встроенные байты не должны остаться в элементе");
        String hash = data.getString("assetId");
        assertTrue(store.exists(hash));
        assertEquals("image/png", data.getString("mime"));
        assertArrayEquals(store.get(hash).orElseThrow(), ImageAssets.prepare(png).bytes());
    }

    @Test
    void migrationIsIdempotentAndSkipsBrokenImages() {
        FileAssetStore store = new FileAssetStore(temp.resolve("assets2"));
        JSONObject already = new JSONObject().put("assetId", "abc");
        assertFalse(AssetMigration.migrateElementData(already, store));

        JSONObject broken = new JSONObject().put("imageData", "data:image/png;base64,zzz");
        assertFalse(AssetMigration.migrateElementData(broken, store));
        assertTrue(broken.has("imageData"), "битая картинка остаётся как была");

        JSONArray elements = new JSONArray()
                .put(new JSONObject().put("elementId", "i1").put("data",
                        already));
        assertEquals(0, AssetMigration.migrateAll(elements, store));
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 8, 6);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
