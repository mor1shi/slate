package org.example.canvasproject.storage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Формат `.slate`: обмен досками вместе с ассетами и защита импорта от
 * вредных архивов. Ошибки не должны оставлять частично сохранённую доску.
 */
class SlateFileTest {

    @TempDir
    Path temp;

    private SqliteStorage storage;
    private LocalBoardService service;
    private Path dbFile;

    @BeforeEach
    void setUp() {
        dbFile = temp.resolve("slate.db");
        storage = new SqliteStorage(dbFile);
        service = new LocalBoardService(storage, new SnapshotStore(dbFile));
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void exportThenImportKeepsBoardAndAssets() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        String hash = storage.assets().put(png);

        LocalBoard board = service.create("С картинкой", 800, 600);
        board.setElements(new JSONArray()
                .put(new JSONObject()
                        .put("elementId", "el-1")
                        .put("elementType", "image")
                        .put("data", new JSONObject()
                                .put("x", 10).put("y", 20)
                                .put("width", 100).put("height", 80)
                                .put("assetId", hash)
                                .put("mime", "image/png"))));
        service.save(board);

        Path file = temp.resolve("export.slate");
        service.exportBoard(board.getId(), file);
        assertTrue(Files.exists(file));

        // Импорт в независимое хранилище доказывает, что ассет лежит в архиве.
        Path otherDir = temp.resolve("other");
        Path otherDb = otherDir.resolve("slate.db");
        try (SqliteStorage other = new SqliteStorage(otherDb)) {
            LocalBoardService otherService = new LocalBoardService(other, new SnapshotStore(otherDb));
            LocalBoard imported = otherService.importBoard(file);

            assertNotEquals(board.getId(), imported.getId());
            assertEquals("С картинкой", imported.getName());
            assertEquals(800, imported.getWidth());
            assertEquals(600, imported.getHeight());
            assertEquals(1, imported.elementCount());

            JSONObject data = imported.getElements().getJSONObject(0).getJSONObject("data");
            assertEquals(hash, data.getString("assetId"));
            assertEquals("image/png", data.getString("mime"));
            assertTrue(other.assets().exists(hash));
            assertEquals(png.length, other.assets().size(hash));
            assertEquals(1, otherService.list().size());
        }
    }

    @Test
    void importCreatesNewBoardAndDoesNotOverwriteExisting() throws Exception {
        LocalBoard original = service.create("Одна", 800, 600);
        original.setElements(new JSONArray().put(new JSONObject()
                .put("elementId", "e1").put("elementType", "note")
                .put("data", new JSONObject().put("text", "привет"))));
        service.save(original);

        Path file = temp.resolve("one.slate");
        service.exportBoard(original.getId(), file);

        LocalBoard first = service.importBoard(file);
        LocalBoard second = service.importBoard(file);

        assertNotEquals(original.getId(), first.getId());
        assertNotEquals(first.getId(), second.getId());
        assertEquals(3, service.list().size());
        // Оригинал не тронут.
        LocalBoard reread = service.find(original.getId()).orElseThrow();
        assertEquals(1, reread.elementCount());
    }

    @Test
    void zipSlipEntryIsRejected() throws Exception {
        Path file = temp.resolve("slip.slate");
        writeZip(file, List.of(
                new Entry("board.json", validBoardJson().toString().getBytes(StandardCharsets.UTF_8)),
                new Entry("assets/../../evil.png", new byte[]{1, 2, 3})));

        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file));
        assertEquals("error.slate.unsafePath", error.key());
        assertTrue(service.list().isEmpty());
    }

    @Test
    void absoluteEntryIsRejected() throws Exception {
        Path file = temp.resolve("absolute.slate");
        writeZip(file, List.of(
                new Entry("board.json", validBoardJson().toString().getBytes(StandardCharsets.UTF_8)),
                new Entry("/tmp/evil.png", new byte[]{1})));

        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file));
        assertEquals("error.slate.unsafePath", error.key());
    }

    @Test
    void tooManyFilesIsRejected() throws Exception {
        java.util.ArrayList<Entry> entries = new java.util.ArrayList<>();
        entries.add(new Entry("board.json", validBoardJson().toString().getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 20; i++) {
            byte[] bytes = {(byte) i};
            entries.add(new Entry("assets/" + FileAssetStore.hash(bytes), bytes));
        }
        Path file = temp.resolve("many.slate");
        writeZip(file, entries);

        SlateFile.Limits limits = new SlateFile.Limits(5, 1024 * 1024, 1024 * 1024, 1024 * 1024);
        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file, limits));
        assertEquals("error.slate.tooManyFiles", error.key());
    }

    @Test
    void tooLargeEntryIsRejected() throws Exception {
        Path file = temp.resolve("big.slate");
        writeZip(file, List.of(
                new Entry("board.json", validBoardJson().toString().getBytes(StandardCharsets.UTF_8)),
                new Entry("assets/" + "a".repeat(64), new byte[4096])));

        SlateFile.Limits limits = new SlateFile.Limits(100, 1024 * 1024, 1024, 1024 * 1024);
        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file, limits));
        assertEquals("error.slate.tooLarge", error.key());
    }

    @Test
    void totalSizeIsLimited() throws Exception {
        Path file = temp.resolve("total.slate");
        writeZip(file, List.of(
                new Entry("board.json", validBoardJson().toString().getBytes(StandardCharsets.UTF_8)),
                new Entry("assets/" + "b".repeat(64), new byte[900]),
                new Entry("assets/" + "c".repeat(64), new byte[900])));

        SlateFile.Limits limits = new SlateFile.Limits(100, 1024 * 1024, 1024, 1024);
        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file, limits));
        assertEquals("error.slate.tooLarge", error.key());
    }

    @Test
    void assetHashMustMatchItsName() throws Exception {
        Path file = temp.resolve("hash.slate");
        writeZip(file, List.of(
                new Entry("board.json", validBoardJson().toString().getBytes(StandardCharsets.UTF_8)),
                new Entry("assets/" + "d".repeat(64), new byte[]{1, 2, 3})));

        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file));
        assertEquals("error.slate.assetMismatch", error.key());
    }

    @Test
    void unknownVersionIsRejectedWithClearError() throws Exception {
        JSONObject json = validBoardJson().put("version", SlateFile.VERSION + 1);
        Path file = temp.resolve("future.slate");
        writeZip(file, List.of(new Entry("board.json",
                json.toString().getBytes(StandardCharsets.UTF_8))));

        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file));
        assertEquals("error.slate.version", error.key());
    }

    @Test
    void invalidJsonIsRejected() throws Exception {
        Path file = temp.resolve("broken.slate");
        writeZip(file, List.of(new Entry("board.json",
                "{not json".getBytes(StandardCharsets.UTF_8))));

        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file));
        assertEquals("error.slate.invalidJson", error.key());
    }

    @Test
    void missingBoardEntryIsRejected() throws Exception {
        Path file = temp.resolve("empty.slate");
        writeZip(file, List.of(new Entry("assets/", new byte[0])));

        SlateFileException error = assertThrows(SlateFileException.class,
                () -> SlateFile.read(file));
        assertEquals("error.slate.noBoard", error.key());
    }

    @Test
    void importFailureLeavesNothingBehind() throws Exception {
        LocalBoard board = service.create("На месте", 800, 600);
        Path file = temp.resolve("bad.slate");
        writeZip(file, List.of(new Entry("board.json",
                validBoardJson().put("version", 99).toString().getBytes(StandardCharsets.UTF_8))));

        assertThrows(SlateFileException.class, () -> service.importBoard(file));
        assertEquals(1, service.list().size());
        assertTrue(service.find(board.getId()).isPresent());
        assertFalse(Files.exists(temp.resolve("other")));
    }

    private static JSONObject validBoardJson() {
        return new JSONObject()
                .put("format", SlateFile.FORMAT)
                .put("version", SlateFile.VERSION)
                .put("id", "imported")
                .put("name", "Импорт")
                .put("width", 800)
                .put("height", 600)
                .put("createdAt", 1)
                .put("updatedAt", 2)
                .put("elements", new JSONArray());
    }

    private record Entry(String name, byte[] bytes) {}

    private static void writeZip(Path file, List<Entry> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.bytes());
                zip.closeEntry();
            }
        }
    }
}
