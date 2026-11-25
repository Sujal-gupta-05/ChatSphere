package com.chatsphere.client.view.workspace;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Centered day separator line: --- 12 Nov 2026 ---
 * Owned by Person 4.
 */
public class DaySeparator extends HBox {
    public DaySeparator(String text) {
        getStyleClass().add("day-separator");
        setAlignment(Pos.CENTER);

        Region leftLine = new Region();
        leftLine.getStyleClass().add("day-separator-line");
        HBox.setHgrow(leftLine, Priority.ALWAYS);

        Label label = new Label(text);
        label.getStyleClass().add("day-separator-label");

        Region rightLine = new Region();
        rightLine.getStyleClass().add("day-separator-line");
        HBox.setHgrow(rightLine, Priority.ALWAYS);

        getChildren().addAll(leftLine, label, rightLine);
    }
}
