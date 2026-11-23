package com.chatsphere.client.component;

import com.chatsphere.client.theme.Spacing;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;

/** Owned by Person 3. Toast layer: bottom-centre, one toast at a time, 3 s each, the rest wait in a queue. Never takes mouse input. */
public class ToastHost extends StackPane {
    private record Item(String text, Severity severity) {}

    private final Deque<Item> queue = new ArrayDeque<>();
    private final PauseTransition timer = new PauseTransition(Duration.seconds(3));
    private boolean showing;

    public ToastHost() {
        setPickOnBounds(false);
        setMouseTransparent(true);
        timer.setOnFinished(e -> {
            getChildren().clear();
            showing = false;
            next();
        });
    }

    /** FX thread only (AppContext.toast takes care of that). */
    public void show(String text, Severity severity) {
        queue.add(new Item(text, severity));
        if (!showing) next();
    }

    private void next() {
        Item item = queue.poll();
        if (item == null) return;
        showing = true;
        Toast toast = new Toast(item.text(), item.severity());
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, Spacing.SPACE_5, 0));
        getChildren().setAll(toast);
        timer.playFromStart();
    }
}
