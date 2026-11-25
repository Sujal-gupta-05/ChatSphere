package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.Avatar;
import com.chatsphere.client.component.IconButton;
import com.chatsphere.client.view.workspace.state.ConversationItem;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel.RightSlotMode;
import com.chatsphere.common.model.TargetType;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Conversation header (64px high) with avatar, title, subtitle and action buttons.
 * Owned by Person 4.
 */
public class ConversationHeader extends HBox {
    public ConversationHeader(ConversationItem item, WorkspaceViewModel workspaceVm) {
        getStyleClass().add("conversation-header");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);

        if (item.type() == TargetType.ROOM) {
            StackPane tile = new StackPane();
            tile.getStyleClass().add("room-tile-large");
            FontIcon hash = new FontIcon("fth-hash");
            hash.setIconSize(20);
            hash.setStyle("-fx-icon-color: -cs-text-secondary;");
            tile.getChildren().add(hash);
            getChildren().add(tile);
        } else {
            Avatar avatar = new Avatar(item.key(), 40);
            avatar.setShowPresence(true);
            avatar.onlineProperty().bind(item.onlineProperty());
            getChildren().add(avatar);
        }

        VBox titleBox = new VBox();
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.setSpacing(2);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Label titleLabel = new Label(item.title());
        titleLabel.getStyleClass().add("header-title");

        Label subtitleLabel = new Label();
        subtitleLabel.getStyleClass().add("header-subtitle");
        if (item.type() == TargetType.ROOM) {
            subtitleLabel.textProperty().bind(item.memberCountProperty().asString("%d members"));
        } else {
            subtitleLabel.textProperty().bind(item.onlineProperty().map(online -> online ? "Online" : "Offline"));
        }

        titleBox.getChildren().addAll(titleLabel, subtitleLabel);
        getChildren().add(titleBox);

        IconButton searchBtn = new IconButton("fth-search", "Search in this conversation");
        searchBtn.setOnAction(e -> {
            workspaceVm.setRightSlotMode(RightSlotMode.SEARCH);
        });

        String detailsIcon = (item.type() == TargetType.ROOM) ? "fth-users" : "fth-info";
        IconButton detailsBtn = new IconButton(detailsIcon, "Details");
        detailsBtn.setOnAction(e -> {
            RightSlotMode current = workspaceVm.rightSlotMode();
            workspaceVm.setRightSlotMode(current == RightSlotMode.DETAILS ? RightSlotMode.NONE : RightSlotMode.DETAILS);
        });

        getChildren().addAll(searchBtn, detailsBtn);

        if (item.type() == TargetType.ROOM) {
            IconButton leaveBtn = new IconButton("fth-log-out", "Leave room");
            leaveBtn.setOnAction(e -> {
                AppContext.get().dialogs().confirm(
                        "Leave room",
                        "You'll stop receiving messages from #" + item.key() + ".",
                        "Leave",
                        () -> AppContext.get().client().leaveRoom(item.key())
                );
            });
            getChildren().add(leaveBtn);
        }
    }
}
