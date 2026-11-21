package org.example.canvasproject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Очистка доски одной командой: удаляет все элементы и умеет вернуть их
 * в прежнем порядке. Уведомления сервера передаются колбэками, чтобы команда
 * оставалась тестируемой без сети.
 */
public class ClearBoardCommand implements command {

    private final BoardModel model;
    private final Consumer<Selectable> onDeleted;
    private final Consumer<Selectable> onRestored;
    private List<Selectable> removed = new ArrayList<>();

    public ClearBoardCommand(BoardModel model,
                             Consumer<Selectable> onDeleted,
                             Consumer<Selectable> onRestored) {
        this.model = model;
        this.onDeleted = onDeleted;
        this.onRestored = onRestored;
    }

    @Override
    public void execute() {
        removed = new ArrayList<>(model.elements());
        for (Selectable el : removed) {
            model.remove(el);
            if (onDeleted != null) onDeleted.accept(el);
        }
    }

    @Override
    public void undo() {
        for (Selectable el : removed) {
            model.add(el);
            if (onRestored != null) onRestored.accept(el);
        }
    }

    public int size() { return removed.size(); }
}
