package org.example.canvasproject.migration;

import org.example.canvasproject.storage.DeviceRecord;
import org.example.canvasproject.storage.InviteRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Полный снимок хранилища: доски с элементами, приглашения и устройства.
 * Снимок не зависит от Mongo/SQLite и одинаково читается в обе стороны.
 */
public record MigrationData(List<BoardSnapshot> boards,
                            List<InviteRecord> invites,
                            List<DeviceRecord> devices,
                            List<String> warnings) {

    public MigrationData {
        boards = boards == null ? List.of() : List.copyOf(boards);
        invites = invites == null ? List.of() : List.copyOf(invites);
        devices = devices == null ? List.of() : List.copyOf(devices);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public MigrationData(List<BoardSnapshot> boards, List<InviteRecord> invites,
                         List<DeviceRecord> devices) {
        this(boards, invites, devices, List.of());
    }

    public int elementCount() {
        int count = 0;
        for (BoardSnapshot board : boards) count += board.elements().size();
        return count;
    }

    /** Счётчики для отчёта и манифеста; порядок ключей стабилен. */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("boards", boards.size());
        counts.put("elements", elementCount());
        counts.put("invites", invites.size());
        counts.put("devices", devices.size());
        return counts;
    }
}
