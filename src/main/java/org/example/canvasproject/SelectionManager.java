package org.example.canvasproject;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;

import java.util.function.Consumer;

public class SelectionManager {

    private final Group group;
    private final BoardModel model;
    private final CommandManager commandManager;
    private SelectionOverlay activeOverlay;

    private Consumer<Selectable> onElementDeleted;
    private Consumer<Selectable> onElementChanged;

    public SelectionManager(Group group, BoardModel model, CommandManager commandManager) {
        this.group = group;
        this.model = model;
        this.commandManager = commandManager;
    }

    public void setOnElementDeleted(Consumer<Selectable> callback) {
        this.onElementDeleted = callback;
    }

    public void setOnElementChanged(Consumer<Selectable> callback) {
        this.onElementChanged = callback;
    }

    public void notifyElementChanged(Selectable el) {
        if (onElementChanged != null) onElementChanged.accept(el);
    }

    public CommandManager getCommandManager() { return commandManager; }
    public BoardModel getModel() { return model; }

    public void attachInteraction(Selectable el) {
        Node node = el.getNode();
        if (node == null) return;
        node.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            if (ev.getClickCount() >= 2 && el.supportsEditMode()) {
                el.enterEditMode();
            } else {
                select(el);
            }
            ev.consume();
        });
    }

    public void add(Selectable el) {
        attachInteraction(el);
        commandManager.execute(new AddCommand(el, model));
        select(el);
    }

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
        if (onElementDeleted != null) onElementDeleted.accept(target);
    }

    public void refreshOverlay() {
        if (activeOverlay != null) activeOverlay.refresh();
    }
}
