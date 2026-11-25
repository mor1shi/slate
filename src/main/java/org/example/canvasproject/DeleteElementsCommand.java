package org.example.canvasproject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Удаление группы элементов одной командой. Undo возвращает каждый элемент
 * на его прежнее место в z-порядке.
 */
public class DeleteElementsCommand implements command {

    private record Entry(int index, Selectable element) {}

    private final BoardModel model;
    private final List<Selectable> elements;
    private final Consumer<List<Selectable>> onDeleted;
    private final Consumer<List<Selectable>> onRestored;
    private List<Entry> entries = List.of();

    public DeleteElementsCommand(BoardModel model, List<Selectable> elements) {
        this(model, elements, null, null);
    }

    public DeleteElementsCommand(BoardModel model, List<Selectable> elements,
                                 Consumer<List<Selectable>> onDeleted,
                                 Consumer<List<Selectable>> onRestored) {
        this.model = model;
        this.elements = new ArrayList<>(elements == null ? List.of() : elements);
        this.onDeleted = onDeleted;
        this.onRestored = onRestored;
    }

    public List<Selectable> getElements() { return List.copyOf(elements); }

    @Override
    public void execute() {
        List<Entry> current = new ArrayList<>();
        for (Selectable el : elements) {
            int index = model.indexOf(el);
            if (index >= 0) current.add(new Entry(index, el));
        }
        current.sort(Comparator.comparingInt(Entry::index));
        entries = current;

        for (Entry entry : current) {
            model.remove(entry.element());
        }
        if (onDeleted != null && !current.isEmpty()) {
            onDeleted.accept(current.stream().map(Entry::element).toList());
        }
    }

    @Override
    public void undo() {
        List<Entry> ascending = new ArrayList<>(entries);
        ascending.sort(Comparator.comparingInt(Entry::index));
        for (Entry entry : ascending) {
            model.insert(entry.index(), entry.element());
        }
        if (onRestored != null && !ascending.isEmpty()) {
            onRestored.accept(ascending.stream().map(Entry::element).toList());
        }
    }
}
