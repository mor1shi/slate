package org.example.canvasproject;

import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Выделение элементов доски: одиночное, рамкой и группой.
 *
 * <p>Выделение — локальное состояние клиента и по сети не рассылается.
 * Наблюдатель может выделять и смотреть свойства, но не может менять элементы:
 * {@link #setEditable(boolean)} убирает ручки и блокирует команды.
 */
public class SelectionManager {

    private final Group group;
    private final BoardModel model;
    private final CommandManager commandManager;

    private final List<Selectable> selection = new ArrayList<>();
    private SelectionOverlay singleOverlay;
    private GroupSelectionOverlay groupOverlay;

    private Consumer<Selectable> onElementDeleted;
    private Consumer<Selectable> onElementChanged;
    private Consumer<List<Selectable>> onElementsDeleted;
    private Consumer<List<Selectable>> onElementsRestored;
    private Consumer<List<Selectable>> onElementsChanged;
    private Consumer<List<Selectable>> onGestureProgress;
    private IntConsumer onSelectionChanged;

    private boolean editable = true;
    private boolean gridSnap = false;
    private double gridSize = 40;

    private List<Selectable> gestureElements = List.of();
    private List<ElementMemento> gestureBefore = List.of();
    private List<Double> gestureBeforeOpacity = List.of();
    private Bounds gestureBounds;
    private double appliedDx;
    private double appliedDy;
    private boolean gestureChanged;

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

    /** Пакетное удаление: одна команда и одно сообщение DELETE_ELEMENTS. */
    public void setOnElementsDeleted(Consumer<List<Selectable>> callback) {
        this.onElementsDeleted = callback;
    }

    public void setOnElementsRestored(Consumer<List<Selectable>> callback) {
        this.onElementsRestored = callback;
    }

    /** Групповое изменение: одна команда и одно пакетное обновление. */
    public void setOnElementsChanged(Consumer<List<Selectable>> callback) {
        this.onElementsChanged = callback;
    }

    /** Промежуточные кадры жеста: вызывающий код решает, как их троттлить. */
    public void setOnGestureProgress(Consumer<List<Selectable>> callback) {
        this.onGestureProgress = callback;
    }

    public void setOnSelectionChanged(IntConsumer callback) {
        this.onSelectionChanged = callback;
    }

    public void notifyElementChanged(Selectable el) {
        if (el != null && model != null) model.refreshSpatialIndex(el);
        if (onElementChanged != null) onElementChanged.accept(el);
        refreshOverlay();
    }

    public CommandManager getCommandManager() { return commandManager; }
    public BoardModel getModel() { return model; }

    /** Наблюдателю можно выделять элементы, но нельзя двигать, ресайзить и удалять. */
    public void setEditable(boolean editable) {
        if (this.editable == editable) return;
        this.editable = editable;
        rebuildOverlay();
    }

    public boolean isEditable() { return editable; }

    public void setGridSnap(boolean gridSnap) { this.gridSnap = gridSnap; }
    public boolean isGridSnap() { return gridSnap; }
    public double getGridSize() { return gridSize; }

    public void attachInteraction(Selectable el) {
        Node node = el.getNode();
        if (node == null) return;
        node.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            if (ev.getClickCount() >= 2 && el.supportsEditMode()) {
                if (editable) el.enterEditMode();
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

    // ---------------------------------------------------------------
    // Состав выделения
    // ---------------------------------------------------------------

    public void select(Selectable el) {
        if (el == null) {
            clearSelection();
            return;
        }
        setSelection(List.of(el));
    }

    public void setSelection(Collection<? extends Selectable> elements) {
        List<Selectable> next = new ArrayList<>();
        if (elements != null) {
            Set<Selectable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Selectable el : elements) {
                if (el != null && seen.add(el)) next.add(el);
            }
        }
        if (sameSelection(next)) return;
        selection.clear();
        selection.addAll(next);
        rebuildOverlay();
    }

    public void addToSelection(Collection<? extends Selectable> elements) {
        if (elements == null || elements.isEmpty()) return;
        boolean changed = false;
        for (Selectable el : elements) {
            if (el != null && !selection.contains(el)) {
                selection.add(el);
                changed = true;
            }
        }
        if (changed) rebuildOverlay();
    }

    public void toggleSelection(Collection<? extends Selectable> elements) {
        if (elements == null) return;
        boolean changed = false;
        for (Selectable el : elements) {
            if (el == null) continue;
            if (selection.contains(el)) {
                selection.remove(el);
                changed = true;
            } else {
                selection.add(el);
                changed = true;
            }
        }
        if (changed) rebuildOverlay();
    }

    public void removeFromSelection(Selectable el) {
        if (selection.remove(el)) rebuildOverlay();
    }

    public void selectAll() {
        if (model == null) return;
        setSelection(model.elements());
    }

    public void clearSelection() {
        boolean had = !selection.isEmpty();
        selection.clear();
        removeOverlays();
        if (had) notifySelectionChanged();
    }

    public boolean isSelected(Selectable el) { return selection.contains(el); }
    public List<Selectable> getSelection() { return List.copyOf(selection); }
    public int selectionCount() { return selection.size(); }

    public Selectable getSelected() {
        return selection.isEmpty() ? null : selection.get(0);
    }

    public Bounds getSelectionBounds() {
        return SelectionGeometry.unionBounds(selection);
    }

    public void setSelectionVisible(boolean visible) {
        if (singleOverlay != null) singleOverlay.setVisible(visible);
        if (groupOverlay != null) groupOverlay.setVisible(visible);
    }

    public void deleteSelected() {
        if (selection.isEmpty() || !editable) return;
        List<Selectable> targets = new ArrayList<>(selection);
        DeleteElementsCommand command = new DeleteElementsCommand(model, targets,
                deleted -> {
                    if (onElementsDeleted != null) onElementsDeleted.accept(deleted);
                    else if (onElementDeleted != null) {
                        for (Selectable el : deleted) onElementDeleted.accept(el);
                    }
                },
                restored -> {
                    if (onElementsRestored != null) onElementsRestored.accept(restored);
                });
        clearSelection();
        commandManager.execute(command);
    }

    // ---------------------------------------------------------------
    // Групповые жесты: перемещение и масштабирование
    // ---------------------------------------------------------------

    public boolean beginGesture() {
        if (selection.isEmpty()) return false;
        gestureElements = new ArrayList<>(selection);
        gestureBefore = new ArrayList<>(gestureElements.size());
        gestureBeforeOpacity = new ArrayList<>(gestureElements.size());
        for (Selectable el : gestureElements) {
            gestureBefore.add(el.save());
            gestureBeforeOpacity.add(GroupChangeCommand.opacityOf(el));
        }
        gestureBounds = SelectionGeometry.unionBounds(gestureElements);
        appliedDx = 0;
        appliedDy = 0;
        gestureChanged = false;
        return gestureBounds != null;
    }

    public Bounds getGestureStartBounds() { return gestureBounds; }

    /** Абсолютное смещение от начала жеста; при включённой сетке вызывающий код привязывает его сам. */
    public void moveGesture(double totalDx, double totalDy) {
        if (gestureElements.isEmpty()) return;
        double dx = totalDx - appliedDx;
        double dy = totalDy - appliedDy;
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return;
        for (Selectable el : gestureElements) {
            el.move(dx, dy);
            model.refreshSpatialIndex(el);
        }
        appliedDx = totalDx;
        appliedDy = totalDy;
        if (Math.abs(totalDx) > 1e-9 || Math.abs(totalDy) > 1e-9) gestureChanged = true;
        refreshOverlay();
        if (gestureChanged && onGestureProgress != null) {
            onGestureProgress.accept(List.copyOf(gestureElements));
        }
    }

    public void resizeGesture(String anchor, double deltaX, double deltaY, boolean keepAspect) {
        if (gestureElements.isEmpty() || gestureBounds == null) return;
        double w = Math.max(gestureBounds.getWidth(), 1);
        double h = Math.max(gestureBounds.getHeight(), 1);
        double sx;
        double sy;
        switch (anchor) {
            case "NW" -> { sx = (w - deltaX) / w; sy = (h - deltaY) / h; }
            case "NE" -> { sx = (w + deltaX) / w; sy = (h - deltaY) / h; }
            case "SW" -> { sx = (w - deltaX) / w; sy = (h + deltaY) / h; }
            default   -> { sx = (w + deltaX) / w; sy = (h + deltaY) / h; }
        }
        if (keepAspect) {
            double s = Math.abs(sx - 1) >= Math.abs(sy - 1) ? sx : sy;
            sx = s;
            sy = s;
        }
        sx = clampScale(sx);
        sy = clampScale(sy);

        double pivotX = anchor.contains("W") ? gestureBounds.getMaxX() : gestureBounds.getMinX();
        double pivotY = anchor.contains("N") ? gestureBounds.getMaxY() : gestureBounds.getMinY();

        for (int i = 0; i < gestureElements.size(); i++) {
            Selectable el = gestureElements.get(i);
            el.restore(gestureBefore.get(i));
            Bounds b = el.getSelectBounds();
            if (b == null) continue;

            double targetMinX = pivotX + (b.getMinX() - pivotX) * sx;
            double targetMinY = pivotY + (b.getMinY() - pivotY) * sy;
            double targetW = Math.max(b.getWidth() * sx, 1);
            double targetH = Math.max(b.getHeight() * sy, 1);

            // resize("SE") сохраняет левый верхний угол, поэтому сначала
            // переносим элемент в целевую точку, затем задаём размер.
            el.move(targetMinX - b.getMinX(), targetMinY - b.getMinY());
            el.resize("SE", targetW, targetH);
            model.refreshSpatialIndex(el);
        }
        if (Math.abs(sx - 1) > 1e-9 || Math.abs(sy - 1) > 1e-9) gestureChanged = true;
        refreshOverlay();
        if (gestureChanged && onGestureProgress != null) {
            onGestureProgress.accept(List.copyOf(gestureElements));
        }
    }

    /** Фиксирует жест одной командой. Возвращает null, если ничего не изменилось. */
    public GroupChangeCommand endGesture() {
        if (gestureElements.isEmpty()) {
            resetGesture();
            return null;
        }
        if (!gestureChanged) {
            resetGesture();
            return null;
        }
        List<GroupChangeCommand.Change> changes = new ArrayList<>();
        for (int i = 0; i < gestureElements.size(); i++) {
            Selectable el = gestureElements.get(i);
            changes.add(new GroupChangeCommand.Change(el, gestureBefore.get(i), el.save(),
                    gestureBeforeOpacity.get(i), GroupChangeCommand.opacityOf(el)));
        }
        GroupChangeCommand command = new GroupChangeCommand(changes);
        commandManager.execute(command);
        for (Selectable el : gestureElements) model.refreshSpatialIndex(el);
        List<Selectable> changed = new ArrayList<>(gestureElements);
        resetGesture();
        if (onElementsChanged != null) onElementsChanged.accept(changed);
        refreshOverlay();
        return command;
    }

    private void resetGesture() {
        gestureElements = List.of();
        gestureBefore = List.of();
        gestureBeforeOpacity = List.of();
        gestureBounds = null;
        appliedDx = 0;
        appliedDy = 0;
        gestureChanged = false;
    }

    private double clampScale(double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale)) return 1;
        return Math.max(0.02, Math.min(50, scale));
    }

    public boolean isTransforming() { return !gestureElements.isEmpty(); }

    // ---------------------------------------------------------------
    // Стили группы
    // ---------------------------------------------------------------

    /** Меняет общий цвет у поддерживающих его элементов; возвращает одну команду. */
    public GroupChangeCommand applyColor(Color color) {
        if (!editable || color == null) return null;
        return applyChange(el -> {
            if (el instanceof figureElement fig) {
                if (color.equals(fig.getFillColor())) return false;
                fig.setFillColor(color);
                return true;
            }
            if (el instanceof lineElement line) {
                Color old = (Color) line.getStroke();
                double alpha = old == null ? 1 : old.getOpacity();
                Color next = Color.color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
                line.setStroke(next);
                return true;
            }
            if (el instanceof customTextField tf) {
                tf.setFontColor(color);
                return true;
            }
            return false;
        });
    }

    /** Толщина применима только к линиям: у остальных типов общего свойства нет. */
    public GroupChangeCommand applyLineWidth(double width) {
        if (!editable) return null;
        return applyChange(el -> {
            if (el instanceof lineElement line) {
                line.setStrokeWidth(width);
                return true;
            }
            return false;
        });
    }

    /** Прозрачность узла; у линий дополнительно меняется alpha обводки. */
    public GroupChangeCommand applyOpacity(double opacity) {
        if (!editable) return null;
        return applyChange(el -> {
            Node node = el.getNode();
            if (node != null) node.setOpacity(opacity);
            if (el instanceof lineElement line && line.getStroke() instanceof Color c) {
                line.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), opacity));
            }
            return node != null || el instanceof lineElement;
        });
    }

    public boolean hasMixedColor() {
        Color first = null;
        boolean any = false;
        for (Selectable el : selection) {
            Color c = colorOf(el);
            if (c == null) continue;
            if (!any) {
                first = c;
                any = true;
            } else if (!c.equals(first)) {
                return true;
            }
        }
        return false;
    }

    public Color commonColor() {
        Color first = null;
        for (Selectable el : selection) {
            Color c = colorOf(el);
            if (c == null) continue;
            if (first == null) first = c;
            else if (!c.equals(first)) return first;
        }
        return first;
    }

    public boolean hasMixedLineWidth() {
        Double first = null;
        for (Selectable el : selection) {
            if (!(el instanceof lineElement line)) continue;
            double w = line.getStrokeWidth();
            if (first == null) first = w;
            else if (Math.abs(first - w) > 1e-9) return true;
        }
        return false;
    }

    public boolean hasMixedOpacity() {
        Double first = null;
        for (Selectable el : selection) {
            Node node = el.getNode();
            if (node == null) continue;
            double o = node.getOpacity();
            if (first == null) first = o;
            else if (Math.abs(first - o) > 1e-9) return true;
        }
        return false;
    }

    /** Цвет — общее свойство, только если его поддерживают ВСЕ выбранные типы. */
    public boolean hasAnyColor() {
        if (selection.isEmpty()) return false;
        for (Selectable el : selection) {
            if (colorOf(el) == null) return false;
        }
        return true;
    }

    /** Толщина линии — общее свойство, только если выбраны одни линии. */
    public boolean hasAnyLine() {
        if (selection.isEmpty()) return false;
        for (Selectable el : selection) {
            if (!(el instanceof lineElement)) return false;
        }
        return true;
    }

    private Color colorOf(Selectable el) {
        if (el instanceof figureElement fig) return fig.getFillColor();
        if (el instanceof lineElement line && line.getStroke() instanceof Color c) return c;
        if (el instanceof customTextField tf) return tf.getFontColor();
        return null;
    }

    private GroupChangeCommand applyChange(java.util.function.Predicate<Selectable> mutator) {
        List<GroupChangeCommand.Change> changes = new ArrayList<>();
        List<Selectable> changedElements = new ArrayList<>();
        for (Selectable el : selection) {
            ElementMemento before = el.save();
            double opacityBefore = GroupChangeCommand.opacityOf(el);
            if (!mutator.test(el)) continue;
            changes.add(new GroupChangeCommand.Change(el, before, el.save(),
                    opacityBefore, GroupChangeCommand.opacityOf(el)));
            changedElements.add(el);
        }
        if (changes.isEmpty()) return null;
        GroupChangeCommand command = new GroupChangeCommand(changes);
        commandManager.execute(command);
        if (onElementsChanged != null) onElementsChanged.accept(changedElements);
        refreshOverlay();
        return command;
    }

    // ---------------------------------------------------------------
    // Удалённые/изменённые другим участником элементы
    // ---------------------------------------------------------------

    /** Элемент исчез с сервера: тихо убираем его из выделения. */
    public void handleRemoteRemoved(Selectable el) {
        if (el == null) return;
        if (selection.remove(el)) rebuildOverlay();
    }

    /** Элемент изменён другим участником: старые границы больше не актуальны. */
    public void handleRemoteChanged(Selectable el) {
        if (el == null) return;
        if (selection.remove(el)) rebuildOverlay();
    }

    // ---------------------------------------------------------------
    // Оверлеи
    // ---------------------------------------------------------------

    public void refreshOverlay() {
        if (singleOverlay != null) singleOverlay.refresh();
        if (groupOverlay != null) groupOverlay.refresh();
    }

    private void rebuildOverlay() {
        removeOverlays();
        if (!selection.isEmpty()) {
            if (selection.size() == 1) {
                singleOverlay = new SelectionOverlay(selection.get(0), this, editable);
                group.getChildren().add(singleOverlay);
                singleOverlay.toFront();
            } else {
                groupOverlay = new GroupSelectionOverlay(selection, this, editable);
                group.getChildren().add(groupOverlay);
                groupOverlay.toFront();
            }
        }
        notifySelectionChanged();
    }

    private void removeOverlays() {
        if (singleOverlay != null) {
            group.getChildren().remove(singleOverlay);
            singleOverlay = null;
        }
        if (groupOverlay != null) {
            group.getChildren().remove(groupOverlay);
            groupOverlay = null;
        }
    }

    private void notifySelectionChanged() {
        if (onSelectionChanged != null) onSelectionChanged.accept(selection.size());
    }

    private boolean sameSelection(List<Selectable> next) {
        return selection.size() == next.size() && selection.equals(next);
    }
}
