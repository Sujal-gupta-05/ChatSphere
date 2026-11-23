package com.chatsphere.client.component;

import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.control.Label;

/** Owned by Person 3. Hidden at 0, shows "99+" above 99. Styled in components.css (amber = unread only). */
public class UnreadBadge extends Label {
    private final IntegerProperty count = new SimpleIntegerProperty(0);

    public UnreadBadge() {
        getStyleClass().add("unread-badge");
        textProperty().bind(Bindings.createStringBinding(
                () -> count.get() > 99 ? "99+" : String.valueOf(count.get()), count));
        visibleProperty().bind(count.greaterThan(0));
        managedProperty().bind(visibleProperty());
        accessibleTextProperty().bind(Bindings.createStringBinding(() -> count.get() + " unread", count));
        setMinWidth(18);
    }

    public IntegerProperty countProperty() { return count; }
}
