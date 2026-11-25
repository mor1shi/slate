package org.example.canvasproject.storage;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Контракт SQLite-хранилища: тот же набор, что у Mongo-адаптера. */
class SqliteStorageContractTest extends StorageContract {

    @TempDir
    Path temp;

    @Override
    protected Storage openStorage() {
        return new SqliteStorage(temp.resolve("contract.db"));
    }
}
