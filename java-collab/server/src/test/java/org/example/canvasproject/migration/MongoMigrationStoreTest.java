package org.example.canvasproject.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Идентификаторы переноса в Mongo: стабильность, сохранение и секреты. */
class MongoMigrationStoreTest {

    @Test
    void preservesExistingObjectId() {
        String id = "6f1a2b3c4d5e6f7a8b9c0d1e";
        assertEquals(id, MongoMigrationStore.objectIdFor(id, "board"));
    }

    @Test
    void derivesStableObjectIdFromUuid() {
        String source = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d";
        String first = MongoMigrationStore.objectIdFor(source, "board");
        String second = MongoMigrationStore.objectIdFor(source, "board");

        assertEquals(first, second, "повторный запуск должен получить тот же id");
        assertTrue(first.matches("[0-9a-f]{24}"), "нужен 24-символьный hex");
        assertNotEquals(first, MongoMigrationStore.objectIdFor(source, "user"),
                "пространства имён не должны совпадать");
    }

    @Test
    void sanitizesMongoUri() {
        assertEquals("mongo:mongodb://***@db.example:27017/canvas",
                MongoMigrationStore.sanitizeUri(
                        "mongo:mongodb://user:secret@db.example:27017/canvas"));
        assertEquals("mongodb://localhost:27017",
                MongoMigrationStore.sanitizeUri("mongodb://localhost:27017"));
    }
}
