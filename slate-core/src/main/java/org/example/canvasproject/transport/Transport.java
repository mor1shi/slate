package org.example.canvasproject.transport;

import org.json.JSONObject;

import java.util.function.Consumer;

/**
 * Канал доставки сообщений протокола. WebSocket-клиент и solo-режим
 * используют один и тот же формат: логика команд не дублируется.
 */
public interface Transport extends AutoCloseable {

    void send(JSONObject message);

    void setListener(Consumer<JSONObject> listener);

    boolean isOpen();

    @Override
    void close();
}
