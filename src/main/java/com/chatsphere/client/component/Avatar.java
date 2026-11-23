package com.chatsphere.client.component;

import javafx.beans.property.BooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/** Owned by Person 3. Colour = avatar-color-(floorMod(username.hashCode(), 8)) so every client agrees. */
public class Avatar extends StackPane {
    private static final int PALETTE_SIZE = 8;
    private final PresenceDot dot = new PresenceDot();

    public Avatar(String username, double size) {
        String name = username == null ? "" : username;
        getStyleClass().add("avatar");

        Circle bg = new Circle(size / 2);
        bg.getStyleClass().addAll("avatar-bg", "avatar-color-" + Math.floorMod(name.hashCode(), PALETTE_SIZE));

        Label initial = new Label(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        initial.getStyleClass().add("avatar-initial");
        initial.setStyle("-fx-font-size: " + Math.round(size * 0.4) + "px;");   // size-dependent only, no colour

        dot.setVisible(false);
        dot.setManaged(false);
        StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);

        getChildren().addAll(bg, initial, dot);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setAccessibleText(name);
    }

    /** Show the presence dot (filled = online, hollow ring = offline). Hidden by default. */
    public void setShowPresence(boolean show) {
        dot.setVisible(show);
        // managed=false keeps the dot out of the layout; it is placed on the bottom-right corner explicitly
        dot.setManaged(false);
        requestLayout();
    }

    public BooleanProperty onlineProperty() { return dot.onlineProperty(); }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // unmanaged children are not positioned by StackPane: tuck the dot into the bottom-right corner
        double r = dot.getRadius() + 1;
        dot.setCenterX(getWidth() - r);
        dot.setCenterY(getHeight() - r);
    }
}
