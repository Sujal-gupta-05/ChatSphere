package com.chatsphere.client.component;

import javafx.scene.control.ProgressIndicator;

/** Owned by Person 3. Indeterminate spinner (ProgressIndicator) used inside buttons and for "loading" states. */
public class Spinner extends ProgressIndicator {
    public Spinner() { this(16); }

    public Spinner(double size) {
        super(ProgressIndicator.INDETERMINATE_PROGRESS);
        getStyleClass().add("cs-spinner");
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setAccessibleText("Loading");
    }
}
