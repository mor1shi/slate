package org.example.canvasproject.storage;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Один набор контрактных тестов для каждой реализации хранилища.
 * Наследники: SQLite (всегда) и Mongo (при доступной базе).
 */
public abstract class StorageContract {

    protected Storage storage;

    protected abstract Storage openStorage();

    @BeforeEach
    void openContractStorage() {
        storage = openStorage();
    }

    @AfterEach
    void closeContractStorage() {
        if (storage != null) storage.close();
    }

    @Test
    void boardIsVisibleOnlyToItsMembers() {
        BoardRecord board = storage.boards().create("Моя доска", "owner-1", 1600, 900);
        assertNotNull(board.id());
        assertEquals("Моя доска", board.name());
        assertEquals(1600, board.width());
        assertEquals("owner", board.roleOf("owner-1"));
        assertTrue(board.canEdit("owner-1"));

        assertEquals(1, storage.boards().listForUser("owner-1").size());
        assertTrue(storage.boards().listForUser("stranger").isEmpty());

        BoardRecord found = storage.boards().find(board.id()).orElseThrow();
        assertEquals(List.of("owner-1"), found.memberIds());
        assertTrue(storage.boards().find("missing").isEmpty());
    }

    @Test
    void renameAndResizePersist() {
        BoardRecord board = storage.boards().create("Старое", "owner", 800, 600);
        assertTrue(storage.boards().rename(board.id(), "Новое"));
        assertTrue(storage.boards().resize(board.id(), 2400, 1400));

        BoardRecord found = storage.boards().find(board.id()).orElseThrow();
        assertEquals("Новое", found.name());
        assertEquals(2400, found.width());
        assertEquals(1400, found.height());
        assertFalse(storage.boards().rename("missing", "x"));
    }

    @Test
    void membersCanBeAddedAndTheirRolesChanged() {
        BoardRecord board = storage.boards().create("Команда", "owner", 800, 600);
        assertTrue(storage.boards().addMember(board.id(), "editor-1", "editor"));
        assertTrue(storage.boards().addMember(board.id(), "viewer-1", "viewer"));

        BoardRecord found = storage.boards().find(board.id()).orElseThrow();
        assertEquals("viewer", found.roleOf("viewer-1"));
        assertTrue(found.canEdit("editor-1"));
        assertFalse(found.canEdit("viewer-1"));

        assertTrue(storage.boards().setRole(board.id(), "viewer-1", "editor"));
        assertEquals("editor", storage.boards().find(board.id()).orElseThrow()
                .roleOf("viewer-1"));

        assertTrue(storage.boards().removeMember(board.id(), "editor-1"));
        assertEquals(null, storage.boards().find(board.id()).orElseThrow().roleOf("editor-1"));
        assertFalse(storage.boards().removeMember(board.id(), "owner"),
                "владельца нельзя удалить из участников");
    }

    @Test
    void deletingBoardCascadesElementsAndInvites() {
        BoardRecord board = storage.boards().create("Удаляемая", "owner", 800, 600);
        storage.elements().add(board.id(), element("e1", "note", "привет"));
        InviteRecord invite = storage.invites().create(board.id(), "owner", "editor", 60_000, 1);

        assertTrue(storage.boards().delete(board.id()));
        assertTrue(storage.boards().find(board.id()).isEmpty());
        assertEquals(0, storage.elements().count(board.id()));
        assertTrue(storage.invites().find(invite.code()).isEmpty());
        assertFalse(storage.boards().delete(board.id()));
    }

    @Test
    void elementsAreStoredInInsertionOrderAndUpsertedById() {
        BoardRecord board = storage.boards().create("Элементы", "owner", 800, 600);
        storage.elements().add(board.id(), element("first", "note", "1"));
        storage.elements().add(board.id(), element("second", "figure", "2"));

        List<ElementRecord> list = storage.elements().list(board.id());
        assertEquals(2, list.size());
        assertEquals("first", list.get(0).elementId());
        assertEquals("second", list.get(1).elementId());

        // Тот же id — замена, не дубль.
        storage.elements().add(board.id(), element("first", "note", "обновлено"));
        assertEquals(2, storage.elements().count(board.id()));
        ElementRecord stored = storage.elements().find(board.id(), "first").orElseThrow();
        assertEquals("обновлено", stored.data().getString("text"));
    }

    @Test
    void elementUpdateAndDeleteReportMissingElements() {
        BoardRecord board = storage.boards().create("Правки", "owner", 800, 600);
        storage.elements().add(board.id(), element("e1", "note", "старое"));

        assertTrue(storage.elements().update(board.id(), "e1",
                new JSONObject().put("text", "новое")));
        assertEquals("новое", storage.elements().find(board.id(), "e1").orElseThrow()
                .data().getString("text"));
        assertFalse(storage.elements().update(board.id(), "missing", new JSONObject()));
        assertTrue(storage.elements().delete(board.id(), "e1"));
        assertFalse(storage.elements().delete(board.id(), "e1"));
    }

    @Test
    void batchUpdateIsAtomicWhenAnItemIsInvalid() {
        BoardRecord board = storage.boards().create("Пакет", "owner", 800, 600);
        storage.elements().add(board.id(), element("e1", "note", "one"));
        storage.elements().add(board.id(), element("e2", "note", "two"));

        storage.elements().updateMany(board.id(), List.of(
                new ElementUpdate("e1", new JSONObject().put("text", "ONE")),
                new ElementUpdate("e2", new JSONObject().put("text", "TWO"))));
        assertEquals("ONE", storage.elements().find(board.id(), "e1").orElseThrow()
                .data().getString("text"));

        assertThrows(IllegalArgumentException.class, () -> storage.elements().updateMany(
                board.id(), List.of(
                        new ElementUpdate("e1", new JSONObject().put("text", "BROKEN")),
                        new ElementUpdate(" ", new JSONObject().put("text", "x")))));
        assertEquals("ONE", storage.elements().find(board.id(), "e1").orElseThrow()
                .data().getString("text"), "первая правка не должна примениться");
    }

    @Test
    void batchDeleteIsAtomicAndCountsRemoved() {
        BoardRecord board = storage.boards().create("Удаление", "owner", 800, 600);
        storage.elements().add(board.id(), element("e1", "note", "1"));
        storage.elements().add(board.id(), element("e2", "note", "2"));
        storage.elements().add(board.id(), element("e3", "note", "3"));

        assertThrows(IllegalArgumentException.class, () -> storage.elements().deleteMany(
                board.id(), List.of("e1", "")));
        assertEquals(3, storage.elements().count(board.id()),
                "при неверном пакете ничего не удаляется");

        assertEquals(2, storage.elements().deleteMany(board.id(), List.of("e1", "e3")));
        assertEquals(1, storage.elements().count(board.id()));
        assertTrue(storage.elements().find(board.id(), "e2").isPresent());
    }

    @Test
    void inviteIsConsumedExactlyMaxUsesTimes() {
        BoardRecord board = storage.boards().create("Приглашения", "owner", 800, 600);
        InviteRecord invite = storage.invites().create(board.id(), "owner", "viewer",
                60_000, 2);
        assertEquals(board.id(), invite.boardId());
        assertEquals("viewer", invite.role());

        Optional<InviteRecord> first = storage.invites().consume(invite.code());
        Optional<InviteRecord> second = storage.invites().consume(invite.code());
        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(2, second.orElseThrow().usedCount());
        assertTrue(storage.invites().consume(invite.code()).isEmpty(),
                "после лимита код недействителен");
    }

    @Test
    void inviteCanBeRevokedAndIsRemovedWithBoard() {
        BoardRecord board = storage.boards().create("Отзыв", "owner", 800, 600);
        InviteRecord first = storage.invites().create(board.id(), "owner", "editor", 60_000, 1);
        assertTrue(storage.invites().revoke(first.code(), board.id()));
        assertTrue(storage.invites().consume(first.code()).isEmpty());

        InviteRecord second = storage.invites().create(board.id(), "owner", "editor", 60_000, 1);
        storage.invites().deleteByBoard(board.id());
        assertTrue(storage.invites().find(second.code()).isEmpty());
    }

    @Test
    void deviceIsFoundByKeyAndProfileIsUpdated() {
        DeviceRecord device = storage.devices().create(new DeviceRecord(null, "Устройство",
                null, null, "device-key-hash-1", "#FF3B30", "", System.currentTimeMillis()));
        assertNotNull(device.id());
        assertEquals(device.id(), storage.devices().findByDeviceKeyHash("device-key-hash-1")
                .orElseThrow().id());
        assertEquals(device.id(), storage.devices().find(device.id()).orElseThrow().id());
        assertEquals(device.id(), storage.devices().findByUsername("Устройство")
                .orElseThrow().id());

        assertTrue(storage.devices().updateProfile(device.id(), "Новое имя", "#00C7BE", "ava"));
        DeviceRecord updated = storage.devices().find(device.id()).orElseThrow();
        assertEquals("Новое имя", updated.username());
        assertEquals("#00C7BE", updated.color());

        assertThrows(StorageException.class, () -> storage.devices().create(
                new DeviceRecord(null, "Другой", null, null, "device-key-hash-1",
                        null, null, System.currentTimeMillis())),
                "ключ устройства уникален");

        assertTrue(storage.devices().delete(device.id()));
        assertTrue(storage.devices().find(device.id()).isEmpty());
    }

    private static ElementRecord element(String id, String type, String text) {
        return new ElementRecord(id, type, new JSONObject().put("text", text),
                "owner", System.currentTimeMillis());
    }
}
