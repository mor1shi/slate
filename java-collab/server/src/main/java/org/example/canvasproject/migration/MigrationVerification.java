package org.example.canvasproject.migration;

import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.DeviceRecord;
import org.example.canvasproject.storage.ElementRecord;
import org.example.canvasproject.storage.InviteRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Сверка источника и назначения после записи. Проверяются все перенесённые
 * сущности и счётчики: доски, элементы (по каждой доске), приглашения,
 * устройства. Лишние записи в назначении не считаются ошибкой — целевая база
 * могла содержать собственные данные; они попадают в общие счётчики.
 */
final class MigrationVerification {

    private static final int MAX_PROBLEMS = 100;

    record Result(List<String> problems, Map<String, Integer> verified) {
        boolean ok() {
            return problems.isEmpty();
        }
    }

    private MigrationVerification() {
    }

    static Result compare(MigrationData source, MigrationData target,
                          Map<String, String> boardIds, Map<String, String> userIds) {
        List<String> problems = new ArrayList<>();
        int verifiedBoards = 0;
        int verifiedElements = 0;
        int verifiedInvites = 0;
        int verifiedDevices = 0;

        Map<String, BoardSnapshot> targetBoards = new HashMap<>();
        for (BoardSnapshot board : target.boards()) targetBoards.put(board.id(), board);

        for (BoardSnapshot snapshot : source.boards()) {
            String targetId = mapId(boardIds, snapshot.id());
            BoardSnapshot actual = targetBoards.get(targetId);
            if (actual == null) {
                problem(problems, "Доска " + snapshot.id() + " не найдена в назначении");
                continue;
            }
            verifiedBoards++;

            BoardRecord expected = snapshot.board();
            BoardRecord found = actual.board();
            same(problems, "Доска " + snapshot.id() + ": имя", expected.name(), found.name());
            same(problems, "Доска " + snapshot.id() + ": владелец",
                    mapId(userIds, expected.ownerId()), found.ownerId());
            same(problems, "Доска " + snapshot.id() + ": ширина", expected.width(), found.width());
            same(problems, "Доска " + snapshot.id() + ": высота", expected.height(), found.height());
            same(problems, "Доска " + snapshot.id() + ": создана",
                    expected.createdAt(), found.createdAt());
            same(problems, "Доска " + snapshot.id() + ": изменена",
                    expected.updatedAt(), found.updatedAt());

            Set<String> expectedMembers = new HashSet<>();
            for (String member : expected.memberIds()) expectedMembers.add(mapId(userIds, member));
            Set<String> foundMembers = new HashSet<>(found.memberIds());
            if (!expectedMembers.equals(foundMembers)) {
                problem(problems, "Доска " + snapshot.id() + ": участники отличаются ("
                        + expectedMembers.size() + " против " + foundMembers.size() + ")");
            }
            Map<String, String> expectedRoles = mapKeys(expected.memberRoles(), userIds);
            if (!expectedRoles.equals(found.memberRoles())) {
                problem(problems, "Доска " + snapshot.id() + ": роли участников отличаются");
            }

            if (snapshot.elements().size() != actual.elements().size()) {
                problem(problems, "Доска " + snapshot.id() + ": элементов ожидалось "
                        + snapshot.elements().size() + ", получено " + actual.elements().size());
            }
            Map<String, ElementRecord> foundElements = new HashMap<>();
            for (ElementRecord element : actual.elements()) {
                foundElements.put(element.elementId(), element);
            }
            for (ElementRecord expectedElement : snapshot.elements()) {
                ElementRecord foundElement = foundElements.get(expectedElement.elementId());
                if (foundElement == null) {
                    problem(problems, "Доска " + snapshot.id() + ": элемент "
                            + expectedElement.elementId() + " не найден");
                    continue;
                }
                verifiedElements++;
                same(problems, "Элемент " + expectedElement.elementId() + ": тип",
                        expectedElement.elementType(), foundElement.elementType());
                same(problems, "Элемент " + expectedElement.elementId() + ": автор",
                        mapId(userIds, expectedElement.addedBy()), foundElement.addedBy());
                same(problems, "Элемент " + expectedElement.elementId() + ": изменён",
                        expectedElement.updatedAt(), foundElement.updatedAt());
                if (!JsonCanonical.same(expectedElement.data(), foundElement.data())) {
                    problem(problems, "Элемент " + expectedElement.elementId()
                            + ": содержимое data отличается");
                }
            }
        }

        Map<String, InviteRecord> targetInvites = new HashMap<>();
        for (InviteRecord invite : target.invites()) targetInvites.put(invite.code(), invite);
        for (InviteRecord expected : source.invites()) {
            InviteRecord found = targetInvites.get(expected.code());
            if (found == null) {
                problem(problems, "Приглашение для доски " + expected.boardId() + " не найдено");
                continue;
            }
            verifiedInvites++;
            same(problems, "Приглашение: доска",
                    mapId(boardIds, expected.boardId()), found.boardId());
            same(problems, "Приглашение: автор",
                    mapId(userIds, expected.createdBy()), found.createdBy());
            same(problems, "Приглашение: роль", expected.role(), found.role());
            same(problems, "Приглашение: срок", expected.expiresAt(), found.expiresAt());
            same(problems, "Приглашение: лимит", expected.maxUses(), found.maxUses());
            same(problems, "Приглашение: использований",
                    expected.usedCount(), found.usedCount());
        }

        Map<String, DeviceRecord> targetDevices = new HashMap<>();
        for (DeviceRecord device : target.devices()) targetDevices.put(device.id(), device);
        for (DeviceRecord expected : source.devices()) {
            DeviceRecord found = targetDevices.get(mapId(userIds, expected.id()));
            if (found == null) {
                problem(problems, "Устройство " + expected.id() + " не найдено");
                continue;
            }
            verifiedDevices++;
            same(problems, "Устройство " + expected.id() + ": имя",
                    expected.username(), found.username());
            same(problems, "Устройство " + expected.id() + ": email",
                    expected.email(), found.email());
            same(problems, "Устройство " + expected.id() + ": пароль",
                    expected.passwordHash(), found.passwordHash());
            same(problems, "Устройство " + expected.id() + ": ключ",
                    expected.deviceKeyHash(), found.deviceKeyHash());
            same(problems, "Устройство " + expected.id() + ": цвет",
                    expected.color(), found.color());
            same(problems, "Устройство " + expected.id() + ": аватар",
                    expected.avatar(), found.avatar());
            same(problems, "Устройство " + expected.id() + ": создано",
                    expected.createdAt(), found.createdAt());
        }

        Map<String, Integer> verified = new LinkedHashMap<>();
        verified.put("boards", verifiedBoards);
        verified.put("elements", verifiedElements);
        verified.put("invites", verifiedInvites);
        verified.put("devices", verifiedDevices);
        return new Result(List.copyOf(problems), Map.copyOf(verified));
    }

    static String mapId(Map<String, String> mapping, String id) {
        if (id == null) return null;
        String mapped = mapping.get(id);
        return mapped == null ? id : mapped;
    }

    private static Map<String, String> mapKeys(Map<String, String> roles,
                                               Map<String, String> userIds) {
        Map<String, String> result = new HashMap<>();
        roles.forEach((key, value) -> result.put(mapId(userIds, key), value));
        return result;
    }

    private static void same(List<String> problems, String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            problem(problems, label + " отличается: ожидалось «" + shortText(expected)
                    + "», получено «" + shortText(actual) + "»");
        }
    }

    private static String shortText(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value);
        return text.length() > 60 ? text.substring(0, 57) + "…" : text;
    }

    private static void problem(List<String> problems, String message) {
        if (problems.size() < MAX_PROBLEMS) {
            problems.add(message);
        } else if (problems.size() == MAX_PROBLEMS) {
            problems.add("…дальнейшие расхождения не показаны");
        }
    }
}
