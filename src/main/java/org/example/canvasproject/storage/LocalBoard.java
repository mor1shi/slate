package org.example.canvasproject.storage;

import org.json.JSONArray;

/**
 * Доска локального хранилища. Элементы лежат в том же JSON-формате, что
 * приходит с сервера (`elementId`, `elementType`, `data`), чтобы одна и та же
 * сериализация работала и в solo-режиме, и при обмене `.slate`.
 */
public final class LocalBoard {

    private String id;
    private String name;
    private int width;
    private int height;
    private long createdAt;
    private long updatedAt;
    private JSONArray elements;

    public LocalBoard(String id, String name, int width, int height,
                      long createdAt, long updatedAt, JSONArray elements) {
        this.id = id;
        this.name = name;
        this.width = width;
        this.height = height;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.elements = elements == null ? new JSONArray() : elements;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public JSONArray getElements() { return elements; }
    public void setElements(JSONArray elements) {
        this.elements = elements == null ? new JSONArray() : elements;
    }

    public int elementCount() { return elements.length(); }

    public LocalBoard copy() {
        return new LocalBoard(id, name, width, height, createdAt, updatedAt,
                new JSONArray(elements.toString()));
    }
}
