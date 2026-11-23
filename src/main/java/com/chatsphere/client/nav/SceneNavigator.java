package com.chatsphere.client.nav;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.ConnectionBanner;
import com.chatsphere.client.component.ToastHost;
import com.chatsphere.client.theme.ThemeManager;
import com.chatsphere.client.view.auth.AuthView;
import com.chatsphere.client.view.workspace.WorkspaceView;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Owned by Person 3. Owns the scene root: a StackPane with three layers [page area, dialog overlay, toast layer].
 * The page area is a BorderPane: ConnectionBanner on top, the current page (Auth or Workspace) in the centre.
 * Requires AppContext.init(...) to have run.
 */
public final class SceneNavigator {
    private static final String SESSION_ENDED_FALLBACK = "You were signed out. Please sign in again.";

    private final Scene scene;
    private final BorderPane page = new BorderPane();
    private final StackPane dialogLayer = new StackPane();
    private final ToastHost toasts = new ToastHost();
    private Node current;

    public SceneNavigator(Stage stage) {
        AppContext ctx = AppContext.get();

        dialogLayer.setPickOnBounds(false);   // empty overlay must not swallow clicks
        page.setTop(new ConnectionBanner(ctx.session(), ctx.client()));
        StackPane root = new StackPane(page, dialogLayer, toasts);

        scene = new Scene(root, 1180, 760);
        ThemeManager.bind(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.titleProperty().bind(Bindings.createStringBinding(() -> {
            int n = ctx.session().totalUnreadProperty().get();
            return n > 0 ? "(" + n + ") ChatSphere" : "ChatSphere";
        }, ctx.session().totalUnreadProperty()));
        stage.setScene(scene);

        ctx.attach(this, toasts);
        ctx.session().setNavigation(
                this::showAuth,
                reason -> showAuth(reason == null || reason.isBlank() ? SESSION_ENDED_FALLBACK : reason));
    }

    public Scene scene() { return scene; }

    /** Overlay layer for dialogs (DialogService adds a scrim + card here). */
    public StackPane dialogLayer() { return dialogLayer; }

    public void showAuth() { showAuth(null); }

    /** notice: optional message shown on the Auth screen (for example why the session ended). */
    public void showAuth(String notice) {
        AppContext.get().dialogs().closeAll();
        setPage(new AuthView(notice));
    }

    public void showWorkspace() {
        AppContext.get().dialogs().closeAll();
        setPage(new WorkspaceView());
    }

    /** Dev harness only (--gallery): shows any node as the page. */
    public void showDevPage(Node node) {
        AppContext.get().dialogs().closeAll();
        setPage(node);
    }

    private void setPage(Node next) {
        if (current instanceof Disposable d) d.dispose();
        current = next;
        page.setCenter(next);
    }
}
