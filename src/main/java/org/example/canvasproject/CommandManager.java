package org.example.canvasproject;

import java.util.ArrayDeque;
import java.util.Deque;

public class CommandManager {
    private final Deque<command> undoStack = new ArrayDeque<>();
    private final Deque<command> redoStack = new ArrayDeque<>();
    private static final int MAX_HISTORY = 100;

    /** Вызывается только при undo/redo: обычное выполнение уже синхронизировано. */
    public interface HistoryListener {
        void commandReapplied(command cmd, boolean redo);
    }

    private HistoryListener historyListener;

    public void setHistoryListener(HistoryListener listener) {
        this.historyListener = listener;
    }

    public void execute(command cmd) {
        cmd.execute();
        undoStack.push(cmd);
        redoStack.clear();
        if (undoStack.size() > MAX_HISTORY) {
            Object[] arr = undoStack.toArray();
            undoStack.clear();
            for (int i = 0; i < arr.length - 1; i++) {
                undoStack.addLast((command) arr[i]);
            }
        }
    }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        command cmd = undoStack.pop();
        cmd.undo();
        redoStack.push(cmd);
        if (historyListener != null) historyListener.commandReapplied(cmd, false);
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        command cmd = redoStack.pop();
        cmd.execute();
        undoStack.push(cmd);
        if (historyListener != null) historyListener.commandReapplied(cmd, true);
        return true;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    public void clear() { undoStack.clear(); redoStack.clear(); }
}