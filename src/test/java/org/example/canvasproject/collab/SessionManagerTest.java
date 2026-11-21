package org.example.canvasproject.collab;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Регрессия H-20/C-07: сообщения сервера доставляются всем слушателям, а не
 * одному перезаписываемому обработчику. Слушатели переживают пересоздание сокета,
 * потому что живут в SessionManager, а не в CollaborationClient.
 */
class SessionManagerTest {

    private final SessionManager session = SessionManager.getInstance();
    private final List<Consumer<JSONObject>> registered = new ArrayList<>();

    private Consumer<JSONObject> register(List<JSONObject> sink) {
        Consumer<JSONObject> listener = sink::add;
        registered.add(listener);
        session.addMessageListener(listener);
        return listener;
    }

    @AfterEach
    void cleanup() {
        registered.forEach(session::removeMessageListener);
        registered.clear();
        session.setConnectionLostListener(null);
    }

    @Test
    void messageReachesBoardAndListListeners() {
        List<JSONObject> board = new ArrayList<>();
        List<JSONObject> list = new ArrayList<>();
        register(board);
        register(list);

        session.dispatch(new JSONObject().put("type", "AUTH_OK"));

        assertEquals("AUTH_OK", board.get(0).getString("type"));
        assertEquals("AUTH_OK", list.get(0).getString("type"));
    }

    @Test
    void removedListenerStopsReceiving() {
        List<JSONObject> board = new ArrayList<>();
        Consumer<JSONObject> listener = register(board);

        session.removeMessageListener(listener);
        registered.remove(listener);
        session.dispatch(new JSONObject().put("type", "ELEMENT_ADDED"));

        assertTrue(board.isEmpty());
    }

    @Test
    void brokenListenerDoesNotBlockOthers() {
        List<JSONObject> healthy = new ArrayList<>();
        Consumer<JSONObject> broken = msg -> {
            throw new IllegalStateException("boom");
        };
        session.addMessageListener(broken);
        registered.add(broken);
        register(healthy);

        session.dispatch(new JSONObject().put("type", "USER_JOINED"));

        assertEquals(1, healthy.size());
    }

    @Test
    void connectionLostListenerFiresOnce() {
        int[] calls = {0};
        session.setConnectionLostListener(() -> calls[0]++);

        session.fireConnectionLost();

        assertEquals(1, calls[0]);
    }
}
