package com.chatsphere.client.component;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.javafx.FontIcon;

/** Owned by Person 3. iconLiteral is an Ikonli Feather literal such as "fth-send". 32x32 minimum hit target. */
public class IconButton extends Button {
    public IconButton(String iconLiteral, String tooltip) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(16);
        setGraphic(icon);
        setTooltip(new Tooltip(tooltip));
        setAccessibleText(tooltip);
        getStyleClass().add("icon-button");
        setMinSize(32, 32);
        setPrefSize(32, 32);
    }

    /** Swaps the icon (for toggle buttons). */
    public void setIcon(String iconLiteral) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(16);
        setGraphic(icon);
    }

    /** Primary-coloured icon, e.g. the Send button while the composer holds sendable text. */
    public void setPrimary(boolean primary) {
        if (primary) { if (!getStyleClass().contains("primary")) getStyleClass().add("primary"); }
        else getStyleClass().remove("primary");
    }
}
