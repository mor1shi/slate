package org.example.canvasproject;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;

public class customPopUp extends Popup {
    private Node owner;
    private double xOffset, yOffset;

    public customPopUp(Node owner, double xOffset, double yOffset) {
        this.owner = owner;
        this.xOffset = xOffset;
        this.yOffset = yOffset;

        setAutoHide(false);

        Scene scene = owner.getScene();
        if (scene != null) {
            Stage stage = (Stage) scene.getWindow();

            stage.xProperty().addListener(
                    (obs, oldVal, newVal) -> updatePosition());
            stage.yProperty().addListener(
                    (obs, oldVal, newVal) -> updatePosition());

            stage.iconifiedProperty().addListener((obs, oldVal, newVal) ->{
                if (newVal) hide();;
            });

            stage.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) hide();
            });

        }
    }
    private void updatePosition() {
        if (isShowing()) {
            setX(owner.localToScreen(0,0).getX() + xOffset);
            setY(owner.localToScreen(0,0).getY() + yOffset);
        }
    }

    @Override
    public void show(Window ownerWindow) {
        super.show(ownerWindow);
        updatePosition();
    }
}
