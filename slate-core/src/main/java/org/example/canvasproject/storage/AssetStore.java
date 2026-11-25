package org.example.canvasproject.storage;

import java.util.Optional;

/**
 * Хранилище файлов, адресуемых SHA-256. Изображения лежат отдельно от
 * элементов, поэтому документ доски не упирается в лимит Mongo в 16 МБ.
 */
public interface AssetStore {

    /** Сохраняет байты и возвращает SHA-256 в hex. Повторная запись не дублируется. */
    String put(byte[] bytes);

    Optional<byte[]> get(String hash);

    boolean exists(String hash);

    long size(String hash);

    boolean delete(String hash);
}
