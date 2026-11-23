package com.chatsphere.client.component;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

/** Owned by Person 3. One toast: severity icon + text. Shown by ToastHost. */
public class Toast extends HBox {
    public Toast(String text, Severity severity) {
        super(8);
        getStyleClass().addAll("toast", "toast-" + severity.name().toLowerCase());
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(420);
        setMaxHeight(USE_PREF_SIZE);
        FontIcon icon = new FontIcon(switch (severity) {
            case INFO -> "fth-info";
            case SUCCESS -> "fth-check-circle";
            case WARNING -> "fth-alert-triangle";
            case ERROR -> "fth-alert-circle";
        });
        icon.setIconSize(16);
        Label label = new Label(text);
        label.setWrapText(true);
        getChildren().addAll(icon, label);
        setAccessibleText(text);
    }
}
