package org.example.canvasproject.storage;

import java.util.List;
import java.util.Optional;

/** Доски и их участники. Поведение одинаково для SQLite и Mongo. */
public interface BoardRepository {

    BoardRecord create(String name, String ownerId, int width, int height);

    Optional<BoardRecord> find(String id);

    List<BoardRecord> listForUser(String userId);

    boolean rename(String id, String name);

    boolean resize(String id, int width, int height);

    /** Удаление доски каскадно убирает элементы, участников и приглашения. */
    boolean delete(String id);

    boolean addMember(String id, String userId, String role);

    boolean removeMember(String id, String userId);

    boolean setRole(String id, String userId, String role);
}
