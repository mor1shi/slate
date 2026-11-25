package org.example.canvasproject.storage;

import java.io.IOException;
import java.util.Arrays;

/**
 * Ошибка формата `.slate` с ключом локализации: интерфейс показывает
 * понятное сообщение, а тесты проверяют причину, не разбирая текст.
 */
public final class SlateFileException extends IOException {

    private final String key;
    private final Object[] args;

    public SlateFileException(String key, Object... args) {
        super(key + (args.length == 0 ? "" : " " + Arrays.toString(args)));
        this.key = key;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public String key() {
        return key;
    }

    public Object[] args() {
        return args.clone();
    }
}
