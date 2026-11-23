package com.chatsphere.client.component;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.javafx.FontIcon;

/** Owned by Person 3. Inline strip: info / success / warning / error, with an optional action link. Icon + text, never colour alone. */
public class Banner extends HBox {
    private final FontIcon icon = new FontIcon();
    private final Label text = new Label();
    private final Button action = new Button();
    private Severity severity;

    public Banner(Severity severity, String message) {
        getStyleClass().add("banner");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);
        setPadding(new Insets(8, 12, 8, 12));
        icon.setIconSize(16);
        text.setText(message);
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        action.getStyleClass().add("banner-action");
        action.setVisible(false);
        action.setManaged(false);
        getChildren().addAll(icon, text, action);
        setSeverity(severity);
    }

    public void setText(String message) { text.setText(message); }

    public void setAction(String label, Runnable onClick) {
        action.setText(label);
        action.setOnAction(e -> onClick.run());
        action.setVisible(true);
        action.setManaged(true);
    }

    /** Hides the action link again. */
    public void clearAction() {
        action.setOnAction(null);
        action.setVisible(false);
        action.setManaged(false);
    }

    /** Changes colour and icon in place (used by the connection banner). */
    public void setSeverity(Severity s) {
        if (severity != null) getStyleClass().remove("banner-" + severity.name().toLowerCase());
        severity = s;
        getStyleClass().add("banner-" + s.name().toLowerCase());
        icon.setIconLiteral(switch (s) {
            case INFO -> "fth-info";
            case SUCCESS -> "fth-check-circle";
            case WARNING -> "fth-alert-triangle";
            case ERROR -> "fth-alert-circle";
        });
    }
}
