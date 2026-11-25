package org.example.canvasproject.transport;

import org.json.JSONObject;

import java.util.function.Consumer;

/**
 * In-process транспорт: сообщения сразу попадают в {@link LocalServer},
 * без сокетов и портов. Формат и проверки те же, что у WebSocket.
 */
public final class InProcessTransport implements Transport {

    /** Подтверждения собственных изменений не возвращаем: WebSocket-сервер тоже
     *  не присылает их автору, иначе клиент применил бы элемент дважды. */
    private static final java.util.Set<String> SELF = java.util.Set.of(
            "ELEMENT_ADDED", "ELEMENT_UPDATED", "ELEMENT_DELETED",
            "ELEMENTS_UPDATED", "ELEMENTS_DELETED", "ASSET_STORED");

    private final LocalServer server;
    private volatile Consumer<JSONObject> listener;
    private volatile boolean open = true;

    public InProcessTransport(LocalServer server) {
        if (server == null) throw new IllegalArgumentException("Нет локального сервера");
        this.server = server;
    }

    @Override
    public void send(JSONObject message) {
        if (!open || message == null) return;
        JSONObject response = server.handle(message);
        Consumer<JSONObject> target = listener;
        if (response != null && target != null && !SELF.contains(response.optString("type"))) {
            target.accept(response);
        }
    }

    @Override
    public void setListener(Consumer<JSONObject> listener) {
        this.listener = listener;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }
}
