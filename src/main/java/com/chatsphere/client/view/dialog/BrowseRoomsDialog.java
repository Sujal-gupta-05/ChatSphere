package com.chatsphere.client.view.dialog;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.component.AppButton;
import com.chatsphere.client.component.Banner;
import com.chatsphere.client.component.DialogFrame;
import com.chatsphere.client.component.Severity;
import com.chatsphere.client.state.RoomFilter;
import com.chatsphere.client.theme.Spacing;
import com.chatsphere.common.model.RoomSummary;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Owned by Person 3. D2: all rooms (listRooms(false)) with a filter, a Join button or "Joined" label per row.
 * The dialog stays open after a join so several rooms can be joined; onJoined(name) fires after each accepted join.
 */
public class BrowseRoomsDialog extends DialogFrame {
    private enum State { LOADING, LOADED, ERROR }

    private final Consumer<String> onJoined;
    private final TextField filter = new TextField();
    private final VBox list = new VBox();
    private final List<RoomSummary> rooms = new ArrayList<>();
    private final Set<String> joining = new HashSet<>();
    private State state = State.LOADING;

    public BrowseRoomsDialog(Consumer<String> onJoined) {
        super("Browse rooms", 480, 520);
        this.onJoined = onJoined;

        filter.setPromptText("Filter rooms");
        filter.setAccessibleText("Filter rooms");
        filter.textProperty().addListener((o, a, b) -> render());

        ScrollPane scroll = new ScrollPane(list);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        body().getChildren().addAll(filter, scroll);

        AppButton close = new AppButton("Close", AppButton.Variant.SECONDARY);
        close.setOnAction(e -> requestClose());
        footer().getChildren().add(close);
        setInitialFocus(filter);
        render();
    }

    @Override
    protected void onShown() { load(); }

    private void load() {
        state = State.LOADING;
        render();
        AppContext.get().client().listRooms(false).whenComplete((result, t) -> {
            if (t == null) {
                rooms.clear();
                rooms.addAll(result);
                state = State.LOADED;
            } else {
                state = State.ERROR;
            }
            render();
        });
    }

    private void render() {
        list.getChildren().clear();
        switch (state) {
            case LOADING -> list.getChildren().add(message("Loading..."));
            case ERROR -> {
                Banner b = new Banner(Severity.ERROR, "Couldn't load rooms");
                b.setAction("Retry", this::load);
                VBox.setMargin(b, new Insets(Spacing.SPACE_3));
                list.getChildren().add(b);
            }
            case LOADED -> {
                List<RoomSummary> shown = RoomFilter.filter(rooms, filter.getText());
                if (rooms.isEmpty()) list.getChildren().add(empty("No rooms yet", "Create the first one from the sidebar."));
                else if (shown.isEmpty()) list.getChildren().add(empty("No rooms match your filter", "Try a different name."));
                else shown.forEach(r -> list.getChildren().add(row(r)));
            }
        }
    }

    private Node row(RoomSummary room) {
        Label name = new Label(room.name());
        name.getStyleClass().add("body-strong");
        name.setTooltip(new Tooltip(room.name()));
        Label members = new Label(room.memberCount() + (room.memberCount() == 1 ? " member" : " members"));
        members.getStyleClass().addAll("small", "text-secondary");
        VBox text = new VBox(2, name, members);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Node action;
        if (room.joined()) {
            Label joined = new Label("Joined", icon("fth-check"));
            joined.getStyleClass().addAll("small", "text-secondary");
            joined.setAccessibleText("Joined " + room.name());
            action = joined;
        } else {
            AppButton join = new AppButton("Join", AppButton.Variant.SECONDARY);
            join.setAccessibleText("Join " + room.name());
            join.setPending(joining.contains(room.name()));
            join.setOnAction(e -> join(room, join));
            action = join;
        }
        HBox row = new HBox(Spacing.SPACE_3, text, action);
        row.getStyleClass().add("dialog-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void join(RoomSummary room, AppButton button) {
        if (!joining.add(room.name())) return;
        button.setPending(true);
        closableProperty().set(false);
        AppContext ctx = AppContext.get();
        ctx.client().joinRoom(room.name()).whenComplete((v, t) -> {
            joining.remove(room.name());
            closableProperty().set(joining.isEmpty());
            if (t == null) {
                for (int i = 0; i < rooms.size(); i++) {
                    RoomSummary r = rooms.get(i);
                    if (r.name().equals(room.name())) {
                        rooms.set(i, new RoomSummary(r.name(), r.memberCount() + 1, true, r.unread(), r.lastText(), r.lastTimestamp()));
                    }
                }
                render();
                onJoined.accept(room.name());
            } else {
                ctx.toast(ErrorMessages.of(t), Severity.ERROR);
                render();
            }
        });
    }

    private static FontIcon icon(String literal) {
        FontIcon i = new FontIcon(literal);
        i.setIconSize(14);
        return i;
    }

    private static Node message(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll("body", "text-secondary");
        l.setPadding(new Insets(Spacing.SPACE_4));
        return l;
    }

    private static Node empty(String title, String hint) {
        Label t = new Label(title);
        t.getStyleClass().add("body-strong");
        Label h = new Label(hint);
        h.getStyleClass().addAll("small", "text-secondary");
        VBox box = new VBox(Spacing.SPACE_1, t, h);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(Spacing.SPACE_6));
        return box;
    }
}
