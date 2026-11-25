package com.chatsphere.client.view.workspace;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.Avatar;
import com.chatsphere.client.component.IconButton;
import com.chatsphere.client.component.PresenceDot;
import com.chatsphere.client.view.workspace.state.ConversationItem;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel;
import com.chatsphere.client.view.workspace.state.WorkspaceViewModel.RightSlotMode;
import com.chatsphere.common.model.MemberInfo;
import com.chatsphere.common.model.TargetType;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Comparator;

/**
 * 280px wide Details pane for rooms and private chats.
 * Owned by Person 4.
 */
public class DetailsPane extends VBox {
    public DetailsPane(ConversationItem item, WorkspaceViewModel workspaceVm) {
        getStyleClass().add("details-pane");
        setPrefWidth(280);

        HBox header = new HBox();
        header.getStyleClass().add("details-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Details");
        title.getStyleClass().add("details-title");
        HBox.setHgrow(title, Priority.ALWAYS);

        IconButton closeBtn = new IconButton("fth-x", "Close details");
        closeBtn.setOnAction(e -> workspaceVm.setRightSlotMode(RightSlotMode.NONE));
        header.getChildren().addAll(title, closeBtn);

        getChildren().add(header);

        VBox content = new VBox();
        content.getStyleClass().add("details-content");

        if (item.type() == TargetType.ROOM) {
            Label name = new Label("#" + item.key());
            name.getStyleClass().add("header-title");

            Label count = new Label();
            count.getStyleClass().add("header-subtitle");
            count.textProperty().bind(item.memberCountProperty().asString("%d members"));

            Label sectionTitle = new Label("MEMBERS");
            sectionTitle.getStyleClass().add("sender-name-label");

            VBox memberListBox = new VBox(6);
            ScrollPane sp = new ScrollPane(memberListBox);
            sp.setFitToWidth(true);
            sp.getStyleClass().add("message-list-scroll");

            content.getChildren().addAll(name, count, sectionTitle, sp);

            AppContext.get().client().listRoomMembers(item.key()).thenAccept(members -> {
                members.sort(Comparator.comparing(MemberInfo::online).reversed()
                        .thenComparing(MemberInfo::username, String.CASE_INSENSITIVE_ORDER));
                memberListBox.getChildren().clear();
                for (MemberInfo m : members) {
                    memberListBox.getChildren().add(new MemberRow(m.username(), m.online()));
                }
            });
        } else {
            VBox profileBox = new VBox(12);
            profileBox.setAlignment(Pos.CENTER);

            Avatar avatar = new Avatar(item.key(), 64);
            avatar.setShowPresence(true);
            avatar.onlineProperty().bind(item.onlineProperty());

            Label name = new Label(item.key());
            name.getStyleClass().add("empty-state-title");

            HBox statusBox = new HBox(6);
            statusBox.setAlignment(Pos.CENTER);
            PresenceDot dot = new PresenceDot();
            dot.onlineProperty().bind(item.onlineProperty());
            Label statusLabel = new Label();
            statusLabel.getStyleClass().add("header-subtitle");
            statusLabel.textProperty().bind(item.onlineProperty().map(o -> o ? "Online" : "Offline"));
            statusBox.getChildren().addAll(dot, statusLabel);

            profileBox.getChildren().addAll(avatar, name, statusBox);
            content.getChildren().add(profileBox);
        }

        getChildren().add(content);
    }
}
