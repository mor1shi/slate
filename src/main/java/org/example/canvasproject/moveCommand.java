package org.example.canvasproject;

public class moveCommand implements command {
    private drawable item;
    private double deltaX;
    private double deltaY;

    public moveCommand(drawable item, double deltaX, double deltaY) {
        this.item = item;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }

    @Override
    public void execute() { item.translate(deltaX, deltaY); }
    @Override
    public void undo() { item.translate(-deltaX, -deltaY); }
}