package org.example.canvasproject.service.repository;

import org.bson.types.ObjectId;
import org.example.canvasproject.models.CanvasDocument;
import org.example.canvasproject.service.MongoDBService;
import org.example.canvasproject.storage.BoardRecord;
import org.example.canvasproject.storage.BoardRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Mongo-адаптер контракта BoardRepository. Поведение — прежнее MongoDBService. */
final class MongoBoardRepository implements BoardRepository {

    private final MongoDBService mongo;

    MongoBoardRepository(MongoDBService mongo) {
        this.mongo = mongo;
    }

    @Override
    public BoardRecord create(String name, String ownerId, int width, int height) {
        CanvasDocument created = mongo.createCanvas(name, ownerId, width, height);
        return toRecord(mongo.findCanvasById(created.getId().toHexString()));
    }

    @Override
    public Optional<BoardRecord> find(String id) {
        return Optional.ofNullable(mongo.findCanvasById(id)).map(MongoBoardRepository::toRecord);
    }

    @Override
    public List<BoardRecord> listForUser(String userId) {
        List<BoardRecord> result = new ArrayList<>();
        for (CanvasDocument canvas : mongo.getCanvasesForUser(userId)) {
            result.add(toRecord(canvas));
        }
        return result;
    }

    @Override
    public boolean rename(String id, String name) {
        Optional<BoardRecord> board = find(id);
        return board.isPresent() && mongo.renameCanvas(id, name, board.get().ownerId());
    }

    @Override
    public boolean resize(String id, int width, int height) {
        Optional<BoardRecord> board = find(id);
        return board.isPresent() && mongo.resizeCanvas(id, width, height, board.get().ownerId());
    }

    @Override
    public boolean delete(String id) {
        Optional<BoardRecord> board = find(id);
        if (board.isEmpty()) return false;
        return mongo.deleteCanvas(id, board.get().ownerId());
    }

    @Override
    public boolean addMember(String id, String userId, String role) {
        try {
            mongo.addMemberToCanvas(id, userId, role);
            return find(id).map(b -> userId.equals(b.ownerId())
                    || b.memberIds().contains(userId)).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean removeMember(String id, String userId) {
        // Владелец остаётся участником всегда: иначе доска потеряет управление.
        BoardRecord board = find(id).orElse(null);
        if (board == null || userId == null || userId.equals(board.ownerId())) return false;
        return mongo.removeMemberFromCanvas(id, userId);
    }

    @Override
    public boolean setRole(String id, String userId, String role) {
        return mongo.setMemberRole(id, userId, role);
    }

    static BoardRecord toRecord(CanvasDocument canvas) {
        if (canvas == null) return null;
        String id = canvas.getId() == null ? null : canvas.getId().toHexString();
        List<String> members = canvas.getMemberIds() == null
                ? List.of() : new ArrayList<>(canvas.getMemberIds());
        Map<String, String> roles = canvas.getMemberRoles() == null
                ? new HashMap<>() : new HashMap<>(canvas.getMemberRoles());
        return new BoardRecord(id, canvas.getName(), canvas.getOwnerId(),
                canvas.getCanvasWidth(), canvas.getCanvasHeight(),
                canvas.getCreatedAt(), canvas.getUpdatedAt(), members, roles);
    }

    static String idOf(ObjectId id) {
        return id == null ? null : id.toHexString();
    }
}
