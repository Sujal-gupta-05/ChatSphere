package com.chatsphere.client.component;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.shape.Circle;

/** Owned by Person 3. Online = filled green, offline = hollow grey ring (never colour alone). Styled in components.css. */
public class PresenceDot extends Circle {
    private final BooleanProperty online = new SimpleBooleanProperty(false);

    public PresenceDot() {
        super(5);
        getStyleClass().add("presence-dot");
        online.addListener((o, was, now) -> update(now));
        update(false);
    }

    private void update(boolean now) {
        if (now) { if (!getStyleClass().contains("online")) getStyleClass().add("online"); }
        else getStyleClass().remove("online");
        setAccessibleText(now ? "Online" : "Offline");
    }

    public BooleanProperty onlineProperty() { return online; }
}
