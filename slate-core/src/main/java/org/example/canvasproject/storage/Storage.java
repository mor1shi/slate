package org.example.canvasproject.storage;

/**
 * Набор репозиториев одной базы. Реализации: SQLite (по умолчанию) и Mongo.
 * Закрывает общее соединение, поэтому репозитории отдельно не закрываются.
 */
public interface Storage extends AutoCloseable {

    BoardRepository boards();

    ElementRepository elements();

    InviteRepository invites();

    DeviceRepository devices();

    AssetStore assets();

    @Override
    void close();
}
