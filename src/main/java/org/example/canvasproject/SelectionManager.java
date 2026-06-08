package org.example.canvasproject;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;

/**
 * Универсальная часть взаимодействия со всеми элементами
 *
 * Делает три вещи
 *   1) attachInteraction — вешает на ноду элемента обработку клика
 *      (одиночный клик = выделить, двойной = редактировать, если поддерживается)
 *   2) select / clearSelection — управляет единственным активным оверлеем
 *   3) add — добавляет элемент через команду (undo-able) и сразу выделяет
 *
 * Чтобы добавить новый тип элемента, контроллеру достаточно вызвать
 * selection.add(new МойЭлемент(...)). Больше ничего писать не нужно
 */
public class SelectionManager {

    private final Group group;
    private final BoardModel model;
    private final CommandManager commandManager;
    private SelectionOverlay activeOverlay;

    public SelectionManager(Group group, BoardModel model, CommandManager commandManager) {
        this.group = group;
        this.model = model;
        this.commandManager = commandManager;
    }

    public CommandManager getCommandManager() { return commandManager; }
    public BoardModel getModel() { return model; }

    public void attachInteraction(Selectable el) {
        Node node = el.getNode();
        if (node == null) return;
        node.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            if (ev.getClickCount() >= 2 && el.supportsEditMode()) {
                el.enterEditMode();            // двойной клик → ввод/редактирование
            } else {
                select(el);                    // одиночный клик → рамка (оверлей)
            }
            ev.consume();
        });
    }

    // Добавить элемент на доску (с записью в историю) и выделить
    public void add(Selectable el) {
        attachInteraction(el);
        commandManager.execute(new AddCommand(el, model));
        select(el);
    }

    // Добавить уже нарисованный/готовый элемент без авто-выделения (например, штрих пера)
    public void addSilently(Selectable el) {
        attachInteraction(el);
        commandManager.execute(new AddCommand(el, model));
    }

    public void select(Selectable el) {
        clearSelection();
        activeOverlay = new SelectionOverlay(el, this);
        group.getChildren().add(activeOverlay);
        activeOverlay.toFront();
    }

    public void clearSelection() {
        if (activeOverlay != null) {
            group.getChildren().remove(activeOverlay);
            activeOverlay = null;
        }
    }

    public Selectable getSelected() {
        return activeOverlay == null ? null : activeOverlay.getTarget();
    }

    public void deleteSelected() {
        if (activeOverlay == null) return;
        Selectable target = activeOverlay.getTarget();
        clearSelection();
        commandManager.execute(new DeleteCommand(target, model));
    }

    public void refreshOverlay() {
        if (activeOverlay != null) activeOverlay.refresh();
    }
}
