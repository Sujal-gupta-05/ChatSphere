package com.chatsphere.client.state;

import com.chatsphere.client.net.ChatClient;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.client.net.ServerEvent;
import javafx.animation.PauseTransition;
import javafx.beans.property.*;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owned by Person 3. Person 4 may read these properties and set totalUnread. Keep the public API.
 * Follows the client's ConnectionStateChanged and SessionEnded events. FX thread only.
 */
public class SessionState {
    private final ChatClient client;
    private final ObjectProperty<String> currentUser = new SimpleObjectProperty<>();
    private final ObjectProperty<ConnectionState> connectionState = new SimpleObjectProperty<>(ConnectionState.OFFLINE);
    private final IntegerProperty totalUnread = new SimpleIntegerProperty();
    private final IntegerProperty retryInSeconds = new SimpleIntegerProperty();
    private final StringProperty serverAddress = new SimpleStringProperty("");
    private final BooleanProperty signingOut = new SimpleBooleanProperty(false);

    private Runnable signedOutHandler = () -> { };
    private Consumer<String> sessionEndedHandler = reason -> { };

    /** Test constructor: no client, so signOut() finishes immediately. */
    public SessionState() { this(null); }

    public SessionState(ChatClient client) {
        this.client = client;
        if (client != null) {
            connectionState.set(client.getConnectionState());
            client.addEventListener(this::onEvent);   // lives as long as the app, so never unsubscribed
        }
    }

    public ObjectProperty<String> currentUserProperty() { return currentUser; }
    public ObjectProperty<ConnectionState> connectionStateProperty() { return connectionState; }
    /** Person 4's WorkspaceViewModel keeps this up to date; the window title shows it. */
    public IntegerProperty totalUnreadProperty() { return totalUnread; }
    public String currentUser() { return currentUser.get(); }

    /** Seconds until the next reconnect attempt (meaningful while RECONNECTING). */
    public IntegerProperty retryInSecondsProperty() { return retryInSeconds; }
    public int retryInSeconds() { return retryInSeconds.get(); }
    public ConnectionState getConnectionState() { return connectionState.get(); }
    /** "host:port" the client last tried to connect to; shown read-only in Settings. */
    public StringProperty serverAddressProperty() { return serverAddress; }
    /** True from signOut() until the Auth screen is shown; the connection banner treats this as logged out. */
    public BooleanProperty signingOutProperty() { return signingOut; }
    public boolean isSigningOut() { return signingOut.get(); }

    /** Called once by the SceneNavigator. */
    public void setNavigation(Runnable onSignedOut, Consumer<String> onSessionEnded) {
        this.signedOutHandler = onSignedOut;
        this.sessionEndedHandler = onSessionEnded;
    }

    /**
     * Logs out, wipes state and goes to Auth. Works from any state: it does not wait for the server for more
     * than two seconds, and a failed logout still signs out locally.
     */
    public void signOut() {
        if (signingOut.get()) return;
        signingOut.set(true);
        AtomicBoolean done = new AtomicBoolean();
        Runnable finish = () -> {
            if (!done.compareAndSet(false, true)) return;
            clear();
            signingOut.set(false);
            signedOutHandler.run();
        };
        if (client == null) { finish.run(); return; }
        PauseTransition fallback = new PauseTransition(Duration.seconds(2));
        fallback.setOnFinished(e -> finish.run());
        fallback.play();
        try {
            client.logout().whenComplete((v, t) -> finish.run());
        } catch (RuntimeException e) {
            finish.run();
        }
    }

    private void clear() {
        currentUser.set(null);
        totalUnread.set(0);
    }

    private void onEvent(ServerEvent e) {
        if (e instanceof ServerEvent.ConnectionStateChanged c) {
            retryInSeconds.set(c.retryInSeconds());
            connectionState.set(c.state());
        } else if (e instanceof ServerEvent.SessionEnded s) {
            clear();
            sessionEndedHandler.accept(s.reason());
        }
    }
}
