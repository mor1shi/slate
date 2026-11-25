package org.example.canvasproject.migration;

import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.DeviceRecord;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.InviteRecord;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Идемпотентность и сверка счётчиков на фейковом источнике — без Mongo и без
 * файлов SQLite.
 */
class MigrationRunnerTest {

    @TempDir
    Path temp;

    @Test
    void repeatedRunIsIdempotentAndWritesManifest() throws IOException {
        FakeStore target = new FakeStore();
        MigrationRunner runner = new MigrationRunner(new FakeStore(sampleData()), target, temp);

        MigrationRunner.Report first = runner.run();
        assertTrue(first.ok(), () -> "проблемы: " + first.problems());
        assertEquals(1, target.boards.size());
        assertEquals(2, target.boards.get("board-1").elements().size());
        assertEquals(1, target.invites.size());
        assertEquals(2, target.devices.size());
        assertTrue(Files.exists(first.backups().get(0)));

        MigrationRunner.Report second = runner.run();
        assertTrue(second.ok(), () -> "проблемы: " + second.problems());
        assertEquals(1, target.boards.size(), "повторный запуск не создаёт досок-дублей");
        assertEquals(2, target.boards.get("board-1").elements().size(),
                "повторный запуск не создаёт элементов-дублей");
        assertEquals(1, target.invites.size());
        assertEquals(2, target.devices.size());

        JSONObject manifest = new JSONObject(Files.readString(
                temp.resolve(MigrationRunner.MANIFEST_NAME)));
        assertEquals("ok", manifest.getString("status"));
        assertEquals(1, manifest.getJSONObject("verified").getInt("boards"));
        assertEquals(2, manifest.getJSONObject("verified").getInt("elements"));
        assertEquals(1, manifest.getJSONObject("verified").getInt("invites"));
        assertEquals(2, manifest.getJSONObject("verified").getInt("devices"));
    }

    @Test
    void completesPartiallyWrittenTarget() {
        MigrationData data = sampleData();
        FakeStore target = new FakeStore();
        target.boards.put("board-1", new BoardSnapshot(data.boards().get(0).board(),
                List.of(data.boards().get(0).elements().get(0))));

        MigrationRunner.Report report = new MigrationRunner(
                new FakeStore(data), target, temp).run();

        assertTrue(report.ok(), () -> "проблемы: " + report.problems());
        assertEquals(2, target.boards.get("board-1").elements().size(),
                "недостающий элемент дозаписан");
        assertEquals(1, target.invites.size(), "приглашение дозаписано");
        assertEquals(2, target.devices.size(), "устройства дозаписаны");
    }

    @Test
    void reportsCountMismatchAndFails() throws IOException {
        FakeStore target = new FakeStore();
        target.dropElementId = "element-2";

        MigrationRunner.Report report = new MigrationRunner(
                new FakeStore(sampleData()), target, temp).run();

        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(
                        problem -> problem.contains("элементов ожидалось 2, получено 1")),
                () -> "проблемы: " + report.problems());
        assertEquals(1, report.verifiedCounts().get("elements"));
        assertEquals("failed", new JSONObject(Files.readString(
                temp.resolve(MigrationRunner.MANIFEST_NAME))).getString("status"));
    }

    @Test
    void reportsMissingInvite() {
        FakeStore target = new FakeStore();
        target.dropInvites = true;

        MigrationRunner.Report report = new MigrationRunner(
                new FakeStore(sampleData()), target, temp).run();

        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(
                problem -> problem.contains("Приглашение для доски")),
                () -> "проблемы: " + report.problems());
    }

    private static MigrationData sampleData() {
        JSONObject first = new JSONObject().put("x", 1).put("y", 2).put("tool", "pen");
        JSONObject second = new JSONObject().put("text", "привет").put("size", 14);
        BoardRecord board = new BoardRecord("board-1", "Доска", "owner-1",
                800, 600, 1000, 2000,
                List.of("owner-1", "user-2"),
                Map.of("owner-1", "owner", "user-2", "viewer"));
        List<ElementRecord> elements = List.of(
                new ElementRecord("element-1", "stroke", first, "owner-1", 1100),
                new ElementRecord("element-2", "text", second, "user-2", 1200));
        InviteRecord invite = new InviteRecord("CODE2345", "board-1", "owner-1",
                "viewer", 1000, 9_999_999_999L, 3, 1);
        DeviceRecord owner = new DeviceRecord("owner-1", "Владелец", null, null,
                "hash-owner", "#ffffff", "", 900);
        DeviceRecord guest = new DeviceRecord("user-2", "Гость", null, null,
                "hash-guest", "#000000", "", 950);
        return new MigrationData(List.of(new BoardSnapshot(board, elements)),
                List.of(invite), List.of(owner, guest));
    }

    /** Мини-хранилище в памяти; повторная запись перезаписывает по id. */
    private static final class FakeStore implements MigrationStore {

        final Map<String, BoardSnapshot> boards = new LinkedHashMap<>();
        final Map<String, InviteRecord> invites = new LinkedHashMap<>();
        final Map<String, DeviceRecord> devices = new LinkedHashMap<>();
        String dropElementId;
        boolean dropInvites;

        FakeStore() {
        }

        FakeStore(MigrationData data) {
            boards.putAll(indexBoards(data.boards()));
            for (InviteRecord invite : data.invites()) invites.put(invite.code(), invite);
            for (DeviceRecord device : data.devices()) devices.put(device.id(), device);
        }

        @Override
        public String description() {
            return "fake";
        }

        @Override
        public MigrationData read() {
            return new MigrationData(new ArrayList<>(boards.values()),
                    new ArrayList<>(invites.values()),
                    new ArrayList<>(devices.values()));
        }

        @Override
        public WriteResult write(MigrationData data) {
            for (BoardSnapshot snapshot : data.boards()) {
                List<ElementRecord> kept = snapshot.elements().stream()
                        .filter(element -> !element.elementId().equals(dropElementId))
                        .toList();
                boards.put(snapshot.id(), new BoardSnapshot(snapshot.board(), kept));
            }
            if (!dropInvites) {
                for (InviteRecord invite : data.invites()) {
                    invites.put(invite.code(), invite);
                }
            }
            for (DeviceRecord device : data.devices()) devices.put(device.id(), device);
            return WriteResult.identity(data, List.of());
        }

        @Override
        public List<Path> backup(Path directory) throws IOException {
            Files.createDirectories(directory);
            Path file = directory.resolve("fake-backup-" + System.nanoTime() + ".txt");
            Files.writeString(file, "backup");
            return List.of(file);
        }

        private static Map<String, BoardSnapshot> indexBoards(List<BoardSnapshot> list) {
            Map<String, BoardSnapshot> result = new LinkedHashMap<>();
            for (BoardSnapshot snapshot : list) result.put(snapshot.id(), snapshot);
            return result;
        }
    }
}
