package com.chatsphere.client.component;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;

/** Owned by Person 3. Two or three segments with an optional unread badge each. Keyboard: Enter/Space select, Left/Right move. */
public class SegmentedTabs extends HBox {
    private final IntegerProperty selectedIndex = new SimpleIntegerProperty(0);
    private final List<UnreadBadge> badges = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    public SegmentedTabs(String... tabLabels) {
        getStyleClass().add("segmented-tabs");
        for (int i = 0; i < tabLabels.length; i++) {
            final int idx = i;
            labels.add(tabLabels[i]);
            UnreadBadge badge = new UnreadBadge();
            badges.add(badge);
            HBox seg = new HBox(6, new Label(tabLabels[i]), badge);
            seg.getStyleClass().add("segment");
            seg.setAlignment(Pos.CENTER);
            seg.setMaxWidth(Double.MAX_VALUE);
            seg.setFocusTraversable(true);
            seg.setAccessibleRole(AccessibleRole.RADIO_BUTTON);
            seg.setOnMouseClicked(e -> selectedIndex.set(idx));
            seg.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) { selectedIndex.set(idx); e.consume(); }
                else if (e.getCode() == KeyCode.RIGHT && idx + 1 < tabLabels.length) { move(idx + 1); e.consume(); }
                else if (e.getCode() == KeyCode.LEFT && idx > 0) { move(idx - 1); e.consume(); }
            });
            HBox.setHgrow(seg, Priority.ALWAYS);
            getChildren().add(seg);
        }
        selectedIndex.addListener((o, oldV, newV) -> refresh());
        badges.forEach(b -> b.countProperty().addListener((o, a, c) -> refresh()));
        refresh();
    }

    private void move(int idx) {
        selectedIndex.set(idx);
        getChildren().get(idx).requestFocus();
    }

    private void refresh() {
        for (int i = 0; i < getChildren().size(); i++) {
            var seg = getChildren().get(i);
            seg.getStyleClass().remove("selected");
            if (i == selectedIndex.get()) seg.getStyleClass().add("selected");
            int unread = badges.get(i).countProperty().get();
            seg.setAccessibleText(labels.get(i) + (unread > 0 ? ", " + unread + " unread" : "")
                    + (i == selectedIndex.get() ? ", selected" : ""));
        }
    }

    public IntegerProperty selectedIndexProperty() { return selectedIndex; }
    public void setBadge(int index, int count) { badges.get(index).countProperty().set(count); }

    /** Greys out one segment so it cannot be chosen (for example "This conversation" with no conversation open). */
    public void setSegmentDisabled(int index, boolean disabled) { getChildren().get(index).setDisable(disabled); }
}
