package org.example.canvasproject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Добавление группы элементов (вставка, дублирование) одной командой.
 */
public class AddElementsCommand implements command {

    private final BoardModel model;
    private final List<Selectable> elements;
    private final Consumer<List<Selectable>> onAdded;
    private final Consumer<List<Selectable>> onRemoved;

    public AddElementsCommand(BoardModel model, List<Selectable> elements) {
        this(model, elements, null, null);
    }

    public AddElementsCommand(BoardModel model, List<Selectable> elements,
                              Consumer<List<Selectable>> onAdded,
                              Consumer<List<Selectable>> onRemoved) {
        this.model = model;
        this.elements = new ArrayList<>(elements == null ? List.of() : elements);
        this.onAdded = onAdded;
        this.onRemoved = onRemoved;
    }

    public List<Selectable> getElements() { return List.copyOf(elements); }

    @Override
    public void execute() {
        for (Selectable el : elements) model.add(el);
        if (onAdded != null && !elements.isEmpty()) onAdded.accept(List.copyOf(elements));
    }

    @Override
    public void undo() {
        for (Selectable el : elements) model.remove(el);
        if (onRemoved != null && !elements.isEmpty()) onRemoved.accept(List.copyOf(elements));
    }
}
