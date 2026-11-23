package com.chatsphere.client.component;

import com.chatsphere.client.theme.Spacing;
import com.chatsphere.client.theme.ThemeManager;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Owned by Person 3. A dialog drawn inside the scene: 40% scrim + a card (radius 12, padding 24, title, body, footer).
 * Esc closes it unless closable is false (a request is running). Tab and Shift+Tab stay inside the card.
 * DialogService adds the frame to the overlay layer, restores focus to the invoker on close and calls the hooks.
 */
public class DialogFrame extends StackPane {
    private final VBox card = new VBox(Spacing.SPACE_4);
    private final VBox body = new VBox(Spacing.SPACE_4);
    private final HBox footer = new HBox(Spacing.SPACE_2);
    private final BooleanProperty closable = new SimpleBooleanProperty(true);
    private Node initialFocus;
    private Node invoker;
    private Runnable closeHandler = () -> { };
    private Runnable onClosed = () -> { };

    /** Height computed from content. */
    public DialogFrame(String title, double width) { this(title, width, -1); }

    /** height &gt; 0 fixes the card height (Browse rooms). */
    public DialogFrame(String title, double width, double height) {
        getStylesheets().add(ThemeManager.css("dialogs.css"));
        getStyleClass().add("dialog-overlay");

        Region scrim = new Region();
        scrim.getStyleClass().add("dialog-scrim");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().addAll("title", "dialog-title");
        titleLabel.setWrapText(true);

        VBox.setVgrow(body, Priority.ALWAYS);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("dialog-footer");

        card.getChildren().addAll(titleLabel, body, footer);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(Spacing.SPACE_5));
        card.setMinWidth(width);
        card.setPrefWidth(width);
        card.setMaxWidth(width);
        if (height > 0) {
            card.setMinHeight(height);
            card.setPrefHeight(height);
            card.setMaxHeight(height);
        } else {
            card.setMaxHeight(Region.USE_PREF_SIZE);
        }
        card.setAccessibleText(title);
        StackPane.setAlignment(card, Pos.CENTER);

        getChildren().addAll(scrim, card);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    // ------------------------------------------------------------------ content
    /** Dialog content goes here. */
    public VBox body() { return body; }

    /** Buttons go here, right-aligned with a gap of 8. */
    public HBox footer() { return footer; }

    /** Set to false while a request runs: Esc and requestClose() then do nothing. */
    public BooleanProperty closableProperty() { return closable; }

    public void setInitialFocus(Node node) { this.initialFocus = node; }

    // ------------------------------------------------------------------ closing
    /** Close if allowed (Esc, Cancel, Close buttons). */
    public void requestClose() {
        if (closable.get()) close();
    }

    /** Close now, regardless of closable (after a successful request). */
    public void close() { closeHandler.run(); }

    // ------------------------------------------------------------------ hooks used by DialogService
    /** One dialog of each kind at a time (Confirm overrides this). */
    public boolean isSingleton() { return true; }
    /** Called by the service right after the frame is in the scene. */
    protected void onShown() { }
    public final void shown() { onShown(); focusInitial(); }
    public final void closed() { onClosed.run(); }
    public void setCloseHandler(Runnable handler) { this.closeHandler = handler; }
    public void setOnClosed(Runnable handler) { this.onClosed = handler; }
    public void setInvoker(Node node) { this.invoker = node; }
    public Node invoker() { return invoker; }

    private void focusInitial() {
        if (initialFocus != null) { initialFocus.requestFocus(); return; }
        List<Node> items = focusables();
        if (!items.isEmpty()) items.get(0).requestFocus();
    }

    // ------------------------------------------------------------------ keyboard
    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            requestClose();
            e.consume();
        } else if (e.getCode() == KeyCode.TAB) {
            List<Node> items = focusables();
            if (!items.isEmpty()) {
                Scene scene = getScene();
                Node owner = scene == null ? null : scene.getFocusOwner();
                int idx = owner == null ? -1 : items.indexOf(owner);
                int last = items.size() - 1;
                int next = e.isShiftDown() ? (idx <= 0 ? last : idx - 1) : (idx < 0 || idx == last ? 0 : idx + 1);
                items.get(next).requestFocus();
            }
            e.consume();   // focus never leaves the dialog
        }
    }

    private List<Node> focusables() {
        List<Node> out = new ArrayList<>();
        collect(card, out);
        return out;
    }

    private static void collect(Node n, List<Node> out) {
        if (!n.isVisible() || !n.isManaged()) return;
        if (n.isFocusTraversable() && !n.isDisabled()) out.add(n);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) collect(c, out);
    }
}
