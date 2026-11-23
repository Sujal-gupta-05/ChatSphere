package com.chatsphere.client.view.dialog;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.AppButton;
import com.chatsphere.client.component.Avatar;
import com.chatsphere.client.component.DialogFrame;
import com.chatsphere.client.component.SegmentedTabs;
import com.chatsphere.client.state.SessionState;
import com.chatsphere.client.theme.Spacing;
import com.chatsphere.client.theme.ThemeManager;
import com.chatsphere.client.theme.ThemeMode;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Owned by Person 3. D3: theme (applied live and saved), server address, signed-in user, Sign out, Done. */
public class SettingsDialog extends DialogFrame {
    public SettingsDialog() {
        super("Settings", 480);
        AppContext ctx = AppContext.get();
        SessionState session = ctx.session();

        // theme: set the initial value first so opening the dialog never rewrites the preference
        SegmentedTabs theme = new SegmentedTabs("Light", "Dark");
        theme.selectedIndexProperty().set(ThemeManager.mode() == ThemeMode.DARK ? 1 : 0);
        theme.selectedIndexProperty().addListener((o, was, now) ->
                ThemeManager.setMode(now.intValue() == 1 ? ThemeMode.DARK : ThemeMode.LIGHT));
        theme.setPrefWidth(240);
        theme.setMaxWidth(240);
        HBox themeRow = new HBox(theme);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        String address = session.serverAddressProperty().get();
        if (address == null || address.isEmpty()) address = ctx.config().host() + ":" + ctx.config().port();
        Label server = new Label(address);
        server.getStyleClass().add("mono");
        server.setTooltip(new javafx.scene.control.Tooltip(address));

        body().getChildren().addAll(
                section("Theme", themeRow),
                section("Server", server),
                section("Signed in as", account(session.currentUser())));

        AppButton signOut = new AppButton("Sign out", AppButton.Variant.DANGER_GHOST).withIcon("fth-log-out");
        signOut.setOnAction(e -> ctx.dialogs().confirm(
                "Sign out?",
                "You'll need to sign in again to see your messages.",
                "Sign out",
                session::signOut));
        AppButton done = new AppButton("Done");
        done.setOnAction(e -> requestClose());
        footer().getChildren().addAll(signOut, done);
        setInitialFocus(theme.getChildren().get(0));
    }

    private static Node section(String label, Node content) {
        Label l = new Label(label);
        l.getStyleClass().addAll("form-label");
        return new VBox(Spacing.SPACE_2, l, content);
    }

    private static Node account(String username) {
        if (username == null) {
            Label none = new Label("Not signed in");
            none.getStyleClass().addAll("body", "text-secondary");
            return none;
        }
        Label name = new Label(username);
        name.getStyleClass().add("body-strong");
        HBox row = new HBox(Spacing.SPACE_3, new Avatar(username, 32), name);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
