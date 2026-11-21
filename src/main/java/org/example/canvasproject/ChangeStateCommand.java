package org.example.canvasproject;

/**
 * Универсальная команда изменения состояния элемента: перемещение, изменение
 * размера, правка текста - всё одно и то же. Хранит снимок до и после
 */
public class ChangeStateCommand implements command {

    private final Selectable element;
    private final ElementMemento before;
    private final ElementMemento after;

    public ChangeStateCommand(Selectable element, ElementMemento before, ElementMemento after) {
        this.element = element;
        this.before = before;
        this.after = after;
    }

    @Override
    public void execute() { element.restore(after); }   // redo (и no-op сразу после live-изменения)

    @Override
    public void undo() { element.restore(before); }
}
