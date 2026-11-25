package com.chatsphere.client.view.workspace;

import com.chatsphere.client.component.Avatar;
import com.chatsphere.client.component.PresenceDot;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Room member row in the Details pane.
 * Owned by Person 4.
 */
public class MemberRow extends HBox {
    public MemberRow(String username, boolean online) {
        getStyleClass().add("member-row");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);

        Avatar avatar = new Avatar(username, 32);
        avatar.setShowPresence(false);

        Label name = new Label(username);
        name.getStyleClass().add("member-name");
        HBox.setHgrow(name, Priority.ALWAYS);

        PresenceDot dot = new PresenceDot();
        dot.onlineProperty().set(online);

        getChildren().addAll(avatar, name, dot);
    }
}
