package org.example.canvasproject.collab;

import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;

import java.util.HashMap;
import java.util.Map;

/**
 * Показывает курсоры других участников на канвасе.
 */
public class RemoteCursorOverlay {

    private final Group layer;
    // userId → курсор
    private final Map<String, CursorNode> cursors = new HashMap<>();

    private static final Color[] PALETTE = {
        Color.web("#FF3B30"), Color.web("#FF9500"), Color.web("#34C759"),
        Color.web("#5AC8FA"), Color.web("#AF52DE"), Color.web("#FF2D55"),
        Color.web("#00C7BE"), Color.web("#FFD60A")
    };
    private int colorIndex = 0;

    public RemoteCursorOverlay(Group parent) {
        this.layer = new Group();
        this.layer.setMouseTransparent(true);
        parent.getChildren().add(layer);
    }

    //Обновляет позицию курсора пользователя, создает при первом появлении.
    public void update(String userId, String username, double x, double y) {
        CursorNode cursor = cursors.computeIfAbsent(userId, id -> {
            CursorNode node = new CursorNode(username, nextColor());
            layer.getChildren().add(node.group);
            return node;
        });
        cursor.moveTo(x, y);
    }

    //Удаляет курсор пользователя
    public void remove(String userId) {
        CursorNode cursor = cursors.remove(userId);
        if (cursor != null) {
            layer.getChildren().remove(cursor.group);
        }
    }

    public void clear() {
        layer.getChildren().clear();
        cursors.clear();
    }

    private Color nextColor() {
        Color c = PALETTE[colorIndex % PALETTE.length];
        colorIndex++;
        return c;
    }


    private static class CursorNode {
        final Group group = new Group();
        final Polygon arrow;
        final Label  nameTag;

        CursorNode(String username, Color color) {
            // Стрелка-курсор
            arrow = new Polygon(
                0.0, 0.0,
                0.0, 20.0,
                5.0, 15.0,
                8.0, 22.0,
                11.0, 20.0,
                8.0, 13.0,
                15.0, 13.0
            );
            arrow.setFill(color);
            arrow.setStroke(Color.WHITE);
            arrow.setStrokeWidth(1.5);

            // Метка с именем
            nameTag = new Label(username);
            nameTag.setFont(Font.font("SF Pro Display", 12));
            nameTag.setStyle(
                "-fx-background-color: " + toHex(color) + ";" +
                "-fx-text-fill: white;" +
                "-fx-padding: 2 6 2 6;" +
                "-fx-background-radius: 6;"
            );
            nameTag.setLayoutX(16);
            nameTag.setLayoutY(18);

            group.getChildren().addAll(arrow, nameTag);
            group.setVisible(false);
        }

        void moveTo(double x, double y) {
            group.setLayoutX(x);
            group.setLayoutY(y);
            group.setVisible(true);
        }

        private String toHex(Color c) {
            return String.format("#%02X%02X%02X",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
        }
    }
}
