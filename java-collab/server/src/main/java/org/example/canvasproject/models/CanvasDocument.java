package org.example.canvasproject.models;

import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;

public class CanvasDocument {
    private ObjectId id;
    private String name;
    private String ownerId;
    private List<String> memberIds = new ArrayList<>();
    private List<CanvasElement> elements = new ArrayList<>();
    private long createdAt;
    private long updatedAt;
    private int canvasWidth  = 1920;
    private int canvasHeight = 1080;

    public CanvasDocument() {}

    public CanvasDocument(String name, String ownerId) {
        this.name = name;
        this.ownerId = ownerId;
        this.memberIds.add(ownerId);
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public ObjectId getId() { return id; }
    public void setId(ObjectId id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public List<String> getMemberIds() { return memberIds; }
    public void setMemberIds(List<String> memberIds) { this.memberIds = memberIds; }
    public List<CanvasElement> getElements() { return elements; }
    public void setElements(List<CanvasElement> elements) { this.elements = elements; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public int getCanvasWidth()  { return canvasWidth; }
    public void setCanvasWidth(int w)  { this.canvasWidth = w; }
    public int getCanvasHeight() { return canvasHeight; }
    public void setCanvasHeight(int h) { this.canvasHeight = h; }
}
