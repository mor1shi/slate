package org.example.canvasproject.storage;

/** Ошибка хранилища: недоступная база, нарушенная транзакция, битые данные. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) { super(message, cause); }
    public StorageException(String message) { super(message); }
}
