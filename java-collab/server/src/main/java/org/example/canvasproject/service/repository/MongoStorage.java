package org.example.canvasproject.service.repository;

import org.example.canvasproject.service.MongoDBService;
import org.example.canvasproject.storage.AssetStore;
import org.example.canvasproject.storage.BoardRepository;
import org.example.canvasproject.storage.DeviceRepository;
import org.example.canvasproject.storage.ElementRepository;
import org.example.canvasproject.storage.FileAssetStore;
import org.example.canvasproject.storage.InviteRepository;
import org.example.canvasproject.storage.Storage;

import java.nio.file.Path;

/**
 * Mongo-реализация Storage. Файлы изображений всегда лежат на диске
 * (AssetStore), а не в документе доски — это снимает лимит 16 МБ.
 */
public final class MongoStorage implements Storage {

    private final MongoDBService mongo;
    private final BoardRepository boards;
    private final ElementRepository elements;
    private final InviteRepository invites;
    private final DeviceRepository devices;
    private final AssetStore assets;

    public MongoStorage(MongoDBService mongo, Path assetsDirectory) {
        this.mongo = mongo;
        mongo.ensureIndexes();
        this.boards = new MongoBoardRepository(mongo);
        this.elements = new MongoElementRepository(mongo);
        this.invites = new MongoInviteRepository(mongo);
        this.devices = new MongoDeviceRepository(mongo);
        this.assets = new FileAssetStore(assetsDirectory);
    }

    public MongoStorage(Path assetsDirectory) {
        this(MongoDBService.getInstance(), assetsDirectory);
    }

    @Override
    public BoardRepository boards() { return boards; }

    @Override
    public ElementRepository elements() { return elements; }

    @Override
    public InviteRepository invites() { return invites; }

    @Override
    public DeviceRepository devices() { return devices; }

    @Override
    public AssetStore assets() { return assets; }

    /** Клиент Mongo общий на процесс: закрывает его владелец, не адаптер. */
    @Override
    public void close() {
        // намеренно пусто
    }
}
