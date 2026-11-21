package org.example.canvasproject.models;

import org.bson.Document;

/**
 * Универсальная модель элемента канваса для хранения в MongoDB
 */
public class CanvasElement {
    private String elementId;
    private String type;
    private Document data; // JSON-документ со всеми параметрами элемента
    private String addedBy;
    private long updatedAt;

    public CanvasElement() {}

    public CanvasElement(String elementId, String type, Document data, String addedBy) {
        this.elementId = elementId;
        this.type = type;
        this.data = data;
        this.addedBy = addedBy;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getElementId() { return elementId; }
    public void setElementId(String elementId) { this.elementId = elementId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Document getData() { return data; }
    public void setData(Document data) { this.data = data; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String addedBy) { this.addedBy = addedBy; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
