package org.example.canvasproject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandManagerTest {

    /** Команда-счётчик: проверяет порядок execute/undo без JavaFX. */
    private static final class CounterCommand implements command {
        final int[] counter;
        final int delta;

        CounterCommand(int[] counter, int delta) {
            this.counter = counter;
            this.delta = delta;
        }

        @Override public void execute() { counter[0] += delta; }
        @Override public void undo()    { counter[0] -= delta; }
    }

    @Test
    void executeUndoRedoKeepsCounterConsistent() {
        CommandManager manager = new CommandManager();
        int[] counter = {0};

        manager.execute(new CounterCommand(counter, 1));
        manager.execute(new CounterCommand(counter, 2));
        assertEquals(3, counter[0]);
        assertTrue(manager.canUndo());
        assertFalse(manager.canRedo());

        assertTrue(manager.undo());
        assertEquals(1, counter[0]);
        assertTrue(manager.canRedo());

        assertTrue(manager.redo());
        assertEquals(3, counter[0]);
    }

    @Test
    void newCommandClearsRedoStack() {
        CommandManager manager = new CommandManager();
        int[] counter = {0};

        manager.execute(new CounterCommand(counter, 1));
        manager.undo();
        manager.execute(new CounterCommand(counter, 5));

        assertFalse(manager.canRedo());
        assertEquals(5, counter[0]);
    }

    @Test
    void historyIsLimitedToHundredEntries() {
        CommandManager manager = new CommandManager();
        int[] counter = {0};

        for (int i = 0; i < 150; i++) {
            manager.execute(new CounterCommand(counter, 1));
        }

        int undone = 0;
        while (manager.undo()) undone++;

        assertEquals(100, undone);
        assertEquals(50, counter[0], "самые старые 50 команд должны быть отброшены");
    }

    @Test
    void clearDropsBothStacks() {
        CommandManager manager = new CommandManager();
        manager.execute(new CounterCommand(new int[]{0}, 1));
        manager.clear();

        assertFalse(manager.canUndo());
        assertFalse(manager.canRedo());
    }

    @Test
    void historyListenerSeesOnlyUndoAndRedo() {
        CommandManager manager = new CommandManager();
        CounterCommand cmd = new CounterCommand(new int[]{0}, 1);
        StringBuilder events = new StringBuilder();
        manager.setHistoryListener((c, redo) ->
                events.append(c == cmd ? "same" : "other").append(redo ? ":redo " : ":undo "));

        manager.execute(cmd);
        assertEquals("", events.toString(), "обычное выполнение не должно дублироваться");

        manager.undo();
        manager.redo();

        assertEquals("same:undo same:redo ", events.toString());
    }
}
