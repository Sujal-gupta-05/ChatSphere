package com.chatsphere.client.view.dialog;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.component.DialogFrame;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Owned by Person 3. Opens dialogs on the overlay layer of the SceneNavigator. Keep the public API.
 * Dialogs stack (Settings -> Confirm); focus returns to whatever had it when the dialog was opened.
 */
public class DialogService {
    private static final Logger LOG = Logger.getLogger(DialogService.class.getName());
    private final Deque<DialogFrame> open = new ArrayDeque<>();   // first = top

    /** Shows D1. onCreated receives the new room name after the server accepted it and the dialog closed. */
    public void showCreateRoom(Consumer<String> onCreated) { show(new CreateRoomDialog(onCreated)); }

    /** Shows D2. onJoined receives each room name the user joined (the dialog stays open so several can be joined). */
    public void showBrowseRooms(Consumer<String> onJoined) { show(new BrowseRoomsDialog(onJoined)); }

    /** Shows D3. */
    public void showSettings() { show(new SettingsDialog()); }

    /** Shows D4. The dialog closes first, then onConfirm runs. */
    public void confirm(String title, String message, String confirmLabel, Runnable onConfirm) {
        show(new ConfirmDialog(title, message, confirmLabel, onConfirm));
    }

    /** Closes every dialog without restoring focus (used when the scene changes). */
    public void closeAll() {
        StackPane layer = layer();
        for (DialogFrame f : new ArrayList<>(open)) f.closed();
        open.clear();
        if (layer != null) layer.getChildren().clear();
    }

    public boolean isOpen() { return !open.isEmpty(); }

    private void show(DialogFrame frame) {
        StackPane layer = layer();
        if (layer == null) { LOG.warning("Dialog requested before the scene exists"); return; }
        if (frame.isSingleton() && open.stream().anyMatch(f -> f.getClass() == frame.getClass())) return;

        Scene scene = layer.getScene();
        frame.setInvoker(scene == null ? null : scene.getFocusOwner());
        frame.setCloseHandler(() -> close(frame));
        open.push(frame);
        layer.getChildren().add(frame);
        Platform.runLater(frame::shown);
    }

    private void close(DialogFrame frame) {
        if (!open.remove(frame)) return;
        StackPane layer = layer();
        if (layer != null) layer.getChildren().remove(frame);
        frame.closed();
        Node invoker = frame.invoker();
        if (invoker != null) {
            Platform.runLater(() -> {
                if (invoker.getScene() != null && invoker.isVisible() && !invoker.isDisabled()) invoker.requestFocus();
            });
        }
    }

    private StackPane layer() {
        try {
            return AppContext.get().nav().dialogLayer();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
