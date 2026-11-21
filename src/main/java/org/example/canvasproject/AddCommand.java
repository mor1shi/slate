package org.example.canvasproject;

// Добавление любого элемента на доску универсально для линий, текста, фото и тд
public class AddCommand implements command {

    private final Selectable element;
    private final BoardModel model;

    public AddCommand(Selectable element, BoardModel model) {
        this.element = element;
        this.model = model;
    }

    public Selectable getElement() { return element; }

    @Override
    public void execute() { model.add(element); }

    @Override
    public void undo() { model.remove(element); }
}
