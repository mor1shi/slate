package org.example.canvasproject.storage;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FileAssetStoreContractTest extends AssetStoreContract {

    @TempDir
    Path temp;

    @Override
    protected AssetStore openStore() {
        return new FileAssetStore(temp.resolve("assets"));
    }
}
