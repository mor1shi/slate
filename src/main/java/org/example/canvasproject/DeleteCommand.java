package org.example.canvasproject;

// Удаление любого элемента с доски
public class DeleteCommand implements command {

    private final Selectable element;
    private final BoardModel model;

    public DeleteCommand(Selectable element, BoardModel model) {
        this.element = element;
        this.model = model;
    }

    @Override
    public void execute() { model.remove(element); }

    @Override
    public void undo() { model.add(element); }
}
