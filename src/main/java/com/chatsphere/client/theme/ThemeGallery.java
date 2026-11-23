package com.chatsphere.client.theme;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.component.*;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.client.net.ServerEvent;
import com.chatsphere.client.view.search.SearchPanel;
import com.chatsphere.common.model.TargetType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Dev-only scratch scene (run with --gallery): every shared component, both themes, so tokens can be eyeballed. */
public final class ThemeGallery {
    private ThemeGallery() {}

    public static Parent create() {
        Button toggle = new Button();
        Runnable label = () -> toggle.setText("Theme: " + ThemeManager.mode() + " (click to switch)");
        toggle.setOnAction(e -> {
            ThemeManager.setMode(ThemeManager.mode() == ThemeMode.LIGHT ? ThemeMode.DARK : ThemeMode.LIGHT);
            label.run();
        });
        label.run();

        VBox page = new VBox(Spacing.SPACE_4);
        page.setPadding(new Insets(Spacing.SPACE_5));
        page.getChildren().addAll(
                toggle,
                section("Type scale", typeScale()),
                section("Avatars 32 / 40 / 64 with presence", avatars()),
                section("Presence dots, unread badges", dotsAndBadges()),
                section("Icon buttons (normal, primary, disabled)", iconButtons()),
                section("Segmented tabs", tabs()),
                section("Banners", banners()),
                section("Session (fake client): sign in first, then try the dialogs and search", session()),
                section("Dialogs", dialogs()),
                section("Search panel (as hosted in the 280 px right slot)", searchPanel()));

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static Node section(String title, Node content) {
        Label l = new Label(title);
        l.getStyleClass().addAll("caption", "text-tertiary");
        VBox box = new VBox(Spacing.SPACE_2, l, content);
        box.setPadding(new Insets(Spacing.SPACE_3));
        box.getStyleClass().add("surface");
        box.setStyle("-fx-border-color: -cs-border; -fx-border-radius: 4;");
        return box;
    }

    private static Node typeScale() {
        VBox v = new VBox(Spacing.SPACE_1);
        String[][] rows = {{"display", "Sign in"}, {"title", "Create a room"}, {"subtitle", "general"},
                {"body-strong", "alice"}, {"body", "Hello, how are you?"}, {"small", "Helper text and errors"},
                {"caption", "12:04"}, {"mono", "localhost"}};
        for (String[] r : rows) {
            Label l = new Label(r[0] + ": " + r[1]);
            l.getStyleClass().add(r[0]);
            v.getChildren().add(l);
        }
        return v;
    }

    private static Node avatars() {
        HBox h = new HBox(Spacing.SPACE_3);
        h.setAlignment(Pos.CENTER_LEFT);
        String[] names = {"alice", "bob", "carol", "dave", "erin", "frank", "grace", "heidi"};
        for (int i = 0; i < names.length; i++) {
            Avatar a = new Avatar(names[i], i < 4 ? 32 : i < 7 ? 40 : 64);
            a.setShowPresence(true);
            a.onlineProperty().set(i % 2 == 0);
            h.getChildren().add(a);
        }
        return h;
    }

    private static Node dotsAndBadges() {
        HBox h = new HBox(Spacing.SPACE_3);
        h.setAlignment(Pos.CENTER_LEFT);
        PresenceDot on = new PresenceDot(), off = new PresenceDot();
        on.onlineProperty().set(true);
        UnreadBadge b0 = new UnreadBadge(), b3 = new UnreadBadge(), b120 = new UnreadBadge();
        b3.countProperty().set(3);
        b120.countProperty().set(120);
        h.getChildren().addAll(on, off, new Label("count 0 is hidden:"), b0, b3, b120);
        return h;
    }

    private static Node iconButtons() {
        HBox h = new HBox(Spacing.SPACE_2);
        IconButton normal = new IconButton("fth-search", "Search");
        IconButton primary = new IconButton("fth-send", "Send");
        primary.setPrimary(true);
        IconButton disabled = new IconButton("fth-send", "Send (disabled)");
        disabled.setDisable(true);
        h.getChildren().addAll(normal, primary, disabled,
                new IconButton("fth-settings", "Settings"), new IconButton("fth-users", "Details"),
                new IconButton("fth-log-out", "Leave"), new IconButton("fth-plus", "New"),
                new IconButton("fth-hash", "Room"), new IconButton("fth-clock", "Sending"),
                new IconButton("fth-check", "Sent"));
        return h;
    }

    private static Node tabs() {
        SegmentedTabs t = new SegmentedTabs("Rooms", "Direct");
        t.setBadge(0, 2);
        t.setBadge(1, 14);
        t.setMaxWidth(280);
        return t;
    }

    private static Node banners() {
        VBox v = new VBox(Spacing.SPACE_2);
        Banner info = new Banner(Severity.INFO, "Back online");
        Banner ok = new Banner(Severity.SUCCESS, "Account created");
        Banner warn = new Banner(Severity.WARNING, "Reconnecting in 4s...");
        warn.setAction("Retry now", () -> { });
        Banner err = new Banner(Severity.ERROR, "Username or password is incorrect.");
        v.getChildren().addAll(info, ok, warn, err);
        return v;
    }

    private static Node session() {
        AppButton signIn = new AppButton("Sign in as demo");
        signIn.setOnAction(e -> {
            AppContext ctx = AppContext.get();
            var client = ctx.client();
            Runnable login = () -> client.login("demo", "password1".toCharArray()).whenComplete((v, t) -> {
                if (t == null) {
                    ctx.session().currentUserProperty().set("demo");
                    ctx.toast("Signed in as demo", Severity.SUCCESS);
                } else {
                    ctx.toast(ErrorMessages.of(t), Severity.ERROR);
                }
            });
            if (client.getConnectionState() == ConnectionState.CONNECTED) {
                login.run();
                return;
            }
            client.connect(ctx.config().host(), ctx.config().port());
            Runnable[] unsubscribe = new Runnable[1];
            unsubscribe[0] = client.addEventListener(ev -> {
                if (ev instanceof ServerEvent.ConnectionStateChanged c && c.state() == ConnectionState.CONNECTED) {
                    unsubscribe[0].run();
                    login.run();
                }
            });
        });
        return new HBox(Spacing.SPACE_2, signIn);
    }

    private static Node dialogs() {
        AppContext ctx = AppContext.get();
        AppButton create = new AppButton("Create room", AppButton.Variant.SECONDARY);
        create.setOnAction(e -> ctx.dialogs().showCreateRoom(name -> ctx.toast("onCreated: " + name, Severity.INFO)));
        AppButton browse = new AppButton("Browse rooms", AppButton.Variant.SECONDARY);
        browse.setOnAction(e -> ctx.dialogs().showBrowseRooms(name -> ctx.toast("onJoined: " + name, Severity.INFO)));
        AppButton settings = new AppButton("Settings", AppButton.Variant.SECONDARY);
        settings.setOnAction(e -> ctx.dialogs().showSettings());
        AppButton confirm = new AppButton("Confirm", AppButton.Variant.SECONDARY);
        confirm.setOnAction(e -> ctx.dialogs().confirm("Leave room?", "You'll stop receiving messages from #general.",
                "Leave", () -> ctx.toast("Confirmed", Severity.INFO)));
        return new HBox(Spacing.SPACE_2, create, browse, settings, confirm);
    }

    private static Node searchPanel() {
        Label status = new Label("Click a result to see it here.");
        status.getStyleClass().addAll("small", "text-secondary");
        SearchPanel panel = new SearchPanel(
                hit -> status.setText("Selected #" + hit.id() + " in " + hit.target() + " by " + hit.sender()),
                () -> status.setText("onClose was called"));
        panel.setCurrentConversation(TargetType.ROOM, "general");
        panel.setPrefHeight(520);
        panel.setMinHeight(520);
        panel.setMaxHeight(520);
        panel.setStyle("-fx-border-color: -cs-border; -fx-border-width: 1;");

        AppButton focus = new AppButton("Focus query", AppButton.Variant.SECONDARY);
        focus.setOnAction(e -> panel.focusQuery());
        AppButton current = new AppButton("Conversation: #general", AppButton.Variant.SECONDARY);
        current.setOnAction(e -> panel.setCurrentConversation(TargetType.ROOM, "general"));
        AppButton privateChat = new AppButton("Conversation: bob", AppButton.Variant.SECONDARY);
        privateChat.setOnAction(e -> panel.setCurrentConversation(TargetType.PRIVATE, "bob"));
        AppButton none = new AppButton("No conversation", AppButton.Variant.SECONDARY);
        none.setOnAction(e -> panel.setCurrentConversation(null, null));

        VBox side = new VBox(Spacing.SPACE_2, focus, current, privateChat, none, status);
        return new HBox(Spacing.SPACE_5, panel, side);
    }
}
