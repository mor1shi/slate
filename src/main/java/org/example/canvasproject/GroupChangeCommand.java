package org.example.canvasproject;

import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Одно групповое изменение (перемещение, масштабирование, стиль) — одна
 * команда Undo. Для каждого элемента хранится точный снимок «до» и «после».
 *
 * <p>Снимок элемента не содержит прозрачности узла, поэтому она хранится
 * рядом: иначе Undo стиля не вернул бы прежнюю прозрачность.
 */
public class GroupChangeCommand implements command {

    /** Пара снимков одного элемента. */
    public record Change(Selectable element, ElementMemento before, ElementMemento after,
                         double beforeOpacity, double afterOpacity) {}

    private final List<Change> changes;

    public GroupChangeCommand(List<Change> changes) {
        this.changes = new ArrayList<>(changes == null ? List.of() : changes);
    }

    public List<Change> getChanges() { return List.copyOf(changes); }

    public List<Selectable> getElements() {
        return changes.stream().map(Change::element).toList();
    }

    public int size() { return changes.size(); }

    /** Текущая прозрачность узла; 1.0, если узла ещё нет. */
    public static double opacityOf(Selectable element) {
        Node node = element == null ? null : element.getNode();
        return node == null ? 1.0 : node.getOpacity();
    }

    @Override
    public void execute() {
        for (Change change : changes) {
            change.element().restore(change.after());
            applyOpacity(change.element(), change.afterOpacity());
        }
    }

    @Override
    public void undo() {
        for (Change change : changes) {
            change.element().restore(change.before());
            applyOpacity(change.element(), change.beforeOpacity());
        }
    }

    private void applyOpacity(Selectable element, double opacity) {
        Node node = element.getNode();
        if (node != null) node.setOpacity(opacity);
    }
}
