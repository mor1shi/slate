package org.example.canvasproject;

import javafx.scene.Group;

import java.util.ArrayList;
import java.util.List;

/**
 * Единая модель доски: один список ВСЕХ элементов + ссылка на группу сцен-графа.
 *
 * Раньше было два параллельных мира (items для линий и textFields для текста) —
 * из-за этого команды/удаление работали по-разному. Теперь всё в одном месте,
 * и add/remove одинаковы для любого типа.
 */
public class BoardModel {

    private final Group group;                       // canvasGroup из контроллера
    private final List<Selectable> elements = new ArrayList<>();

    public BoardModel(Group group) {
        this.group = group;
    }

    public void add(Selectable el) {
        if (!elements.contains(el)) {
            elements.add(el);
            if (el.getNode() != null && !group.getChildren().contains(el.getNode())) {
                group.getChildren().add(el.getNode());
            }
        }
    }

    public void remove(Selectable el) {
        elements.remove(el);
        if (el.getNode() != null) {
            group.getChildren().remove(el.getNode());
        }
    }

    public List<Selectable> elements() {
        return elements;
    }

    /** Верхний (последний добавленный) элемент под точкой. */
    public Selectable findAt(double x, double y) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            Selectable el = elements.get(i);
            if (el.contains(x, y)) return el;
        }
        return null;
    }
}
