package org.example.canvasproject.service.repository;

import com.mongodb.client.MongoClients;
import org.example.canvasproject.storage.Storage;
import org.example.canvasproject.storage.StorageContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Те же контрактные тесты, что у SQLite, но против Mongo. Требует
 * SLATE_INTEGRATION_TESTS=1 и тестовую базу (MONGO_DB содержит "test").
 */
class MongoStorageContractTest extends StorageContract {

    private static final String DB_NAME = System.getenv().getOrDefault("MONGO_DB", "");

    @TempDir
    Path temp;

    @BeforeEach
    void requireMongoTestDatabase() {
        Assumptions.assumeTrue("1".equals(System.getenv("SLATE_INTEGRATION_TESTS")),
                "Интеграционные тесты выключены (SLATE_INTEGRATION_TESTS != 1)");
        Assumptions.assumeTrue(DB_NAME.contains("test"),
                "MONGO_DB должен указывать на тестовую базу (содержать 'test')");
        wipe();
    }

    @AfterEach
    void wipeAfter() {
        wipe();
    }

    private static void wipe() {
        // Без MONGO_DB интеграционные тесты пропускаются, но @AfterEach всё
        // равно вызывается: не открываем Mongo с пустым именем базы.
        if (DB_NAME.isBlank()) return;
        String uri = System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
        try (var client = MongoClients.create(uri)) {
            // Удаляем документы, но не базу: индексы (уникальный ключ устройства)
            // должны жить между тестами, как в рабочем развёртывании.
            var db = client.getDatabase(DB_NAME);
            for (String name : new String[]{"users", "canvases", "invite_codes", "device_tokens"}) {
                db.getCollection(name).deleteMany(new org.bson.Document());
            }
        }
    }

    @Override
    protected Storage openStorage() {
        return new MongoStorage(temp.resolve("assets"));
    }
}
