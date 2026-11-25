package org.example.canvasproject.migration;

import java.util.List;
import java.util.Map;

/**
 * Что записано в назначение. Карты id нужны, когда хранилище не может
 * сохранить исходный id (Mongo требует ObjectId для досок, пользователей и
 * приглашений): проверка сверяет данные уже по новым id.
 */
public record WriteResult(Map<String, String> boardIds,
                          Map<String, String> userIds,
                          int boards,
                          int elements,
                          int invites,
                          int devices,
                          List<String> warnings) {

    public WriteResult {
        boardIds = boardIds == null ? Map.of() : Map.copyOf(boardIds);
        userIds = userIds == null ? Map.of() : Map.copyOf(userIds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** id сохраняется, если хранилище умеет писать исходные идентификаторы. */
    public static WriteResult identity(MigrationData data, List<String> warnings) {
        return new WriteResult(Map.of(), Map.of(), data.boards().size(),
                data.elementCount(), data.invites().size(), data.devices().size(),
                warnings);
    }
}
