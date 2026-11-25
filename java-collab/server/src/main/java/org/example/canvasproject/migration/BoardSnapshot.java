package org.example.canvasproject.migration;

import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.ElementRecord;

import java.util.List;

/** Доска вместе с её элементами — единица переноса и сверки. */
public record BoardSnapshot(BoardRecord board, List<ElementRecord> elements) {

    public BoardSnapshot {
        if (board == null) throw new IllegalArgumentException("Нет доски");
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public String id() {
        return board.id();
    }
}
