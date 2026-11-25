package org.example.canvasproject.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Контракт хранилища ассетов: адресация по SHA-256 и дедупликация. */
public abstract class AssetStoreContract {

    protected abstract AssetStore openStore();

    @Test
    void sameBytesAreStoredOnceUnderSha256() {
        AssetStore store = openStore();
        byte[] bytes = "одинаковые байты".getBytes(StandardCharsets.UTF_8);
        String first = store.put(bytes);
        String second = store.put(bytes.clone());

        assertEquals(first, second);
        assertEquals(FileAssetStore.hash(bytes), first);
        assertEquals(bytes.length, store.size(first));
        assertTrue(store.exists(first));
        Optional<byte[]> loaded = store.get(first);
        assertTrue(loaded.isPresent());
        assertArrayEquals(bytes, loaded.orElseThrow());
    }

    @Test
    void differentBytesProduceDifferentHashes() {
        AssetStore store = openStore();
        String a = store.put("a".getBytes(StandardCharsets.UTF_8));
        String b = store.put("b".getBytes(StandardCharsets.UTF_8));
        assertFalse(a.equals(b));
        assertTrue(store.delete(a));
        assertFalse(store.exists(a));
        assertFalse(store.delete(a));
    }

    @Test
    void invalidHashesDoNotEscapeTheStore() {
        AssetStore store = openStore();
        assertTrue(store.get("../../etc/passwd").isEmpty());
        assertFalse(store.exists("../slate.db"));
        assertEquals(-1, store.size("not-a-hash"));
        assertFalse(store.delete("../../x"));
    }

    @Test
    void tooLargeAssetIsRejected() {
        AssetStore store = openStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.put(new byte[(int) FileAssetStore.DEFAULT_MAX_BYTES + 1]));
    }
}
