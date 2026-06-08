package org.example.canvasproject;

import java.util.List;

public class drawStrokeCommand implements command {
    private final lineDrawable stroke;
    private final List<drawable> items;

    public drawStrokeCommand(lineDrawable stroke, List<drawable> items) {
        this.stroke = stroke;
        this.items = items;
    }

    @Override
    public void execute() {
        if (!items.contains(stroke)) items.add(stroke);
    }

    @Override
    public void undo() {
        items.remove(stroke);
    }
}