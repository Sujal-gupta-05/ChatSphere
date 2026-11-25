package com.chatsphere.client.view.workspace;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * "N unread messages" accent divider.
 * Owned by Person 4.
 */
public class UnreadDividerView extends HBox {
    public UnreadDividerView(String text) {
        getStyleClass().add("unread-divider");
        setAlignment(Pos.CENTER);

        Region leftLine = new Region();
        leftLine.getStyleClass().add("unread-divider-line");
        HBox.setHgrow(leftLine, Priority.ALWAYS);

        StackPane pill = new StackPane();
        pill.getStyleClass().add("unread-divider-pill");
        Label label = new Label(text);
        label.getStyleClass().add("unread-divider-text");
        pill.getChildren().add(label);

        Region rightLine = new Region();
        rightLine.getStyleClass().add("unread-divider-line");
        HBox.setHgrow(rightLine, Priority.ALWAYS);

        getChildren().addAll(leftLine, pill, rightLine);
    }
}
