package com.chatsphere.client.component;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Owned by Person 3. 36 px button in five variants. While pending it shows a spinner instead of the label at
 * the same width and ignores clicks (it is NOT disabled, so you may still bind disableProperty to a validity flag).
 */
public class AppButton extends Button {
    public enum Variant {
        PRIMARY("primary"), SECONDARY("secondary"), GHOST("ghost"), DANGER("danger"), DANGER_GHOST("danger-ghost");
        private final String css;
        Variant(String css) { this.css = css; }
        String css() { return css; }
    }

    private final BooleanProperty pending = new SimpleBooleanProperty(false);
    private Node idleGraphic;
    private String idleText;
    private String idleAccessibleText;
    private double idleMinWidth = USE_COMPUTED_SIZE;

    public AppButton(String text) { this(text, Variant.PRIMARY); }

    public AppButton(String text, Variant variant) {
        super(text);
        getStyleClass().addAll("app-button", variant.css());
        setMnemonicParsing(false);
        pending.addListener((o, was, now) -> applyPending(now));
    }

    /** Leading Feather icon, e.g. "fth-plus". */
    public AppButton withIcon(String iconLiteral) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(16);
        setGraphic(icon);
        return this;
    }

    /** 40 px high (the Auth submit button). */
    public AppButton large() {
        getStyleClass().add("large");
        return this;
    }

    public BooleanProperty pendingProperty() { return pending; }
    public void setPending(boolean value) { pending.set(value); }
    public boolean isPending() { return pending.get(); }

    @Override
    public void fire() {
        if (!pending.get()) super.fire();
    }

    private void applyPending(boolean now) {
        if (now) {
            idleText = getText();
            idleAccessibleText = getAccessibleText();
            idleGraphic = getGraphic();
            idleMinWidth = getMinWidth();
            if (getWidth() > 0) setMinWidth(getWidth());   // width unchanged while the label is gone
            setText("");
            setGraphic(new Spinner(16));
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            getStyleClass().add("pending");
            setAccessibleText((idleAccessibleText != null ? idleAccessibleText : idleText) + ", working");
        } else {
            getStyleClass().remove("pending");
            setText(idleText);
            setAccessibleText(idleAccessibleText);
            setGraphic(idleGraphic);
            setContentDisplay(ContentDisplay.LEFT);
            setMinWidth(idleMinWidth);
        }
    }
}
