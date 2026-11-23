package com.chatsphere.client.component;

import com.chatsphere.client.net.ChatClient;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.client.state.SessionState;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.util.Duration;

/**
 * Owned by Person 3. Full-width strip at the top of every scene, bound to SessionState:
 * RECONNECTING -> warning with a countdown and "Retry now"; OFFLINE while signed in -> error with "Retry now";
 * back to CONNECTED after a problem -> "Back online" for 2 s. Otherwise hidden.
 */
public class ConnectionBanner extends Banner {
    private final SessionState session;
    private final Runnable retry;
    private final Timeline countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
    private final PauseTransition backOnline = new PauseTransition(Duration.seconds(2));
    private boolean problemShown;
    private int remaining;

    public ConnectionBanner(SessionState session, ChatClient client) {
        super(Severity.WARNING, "");
        this.session = session;
        this.retry = client::retryNow;
        getStyleClass().add("banner-flat");
        countdown.setCycleCount(Animation.INDEFINITE);
        backOnline.setOnFinished(e -> hide());

        session.connectionStateProperty().addListener((o, a, b) -> { remaining = session.retryInSeconds(); update(); });
        session.retryInSecondsProperty().addListener((o, a, b) -> { remaining = b.intValue(); update(); });
        session.currentUserProperty().addListener((o, a, b) -> update());
        session.signingOutProperty().addListener((o, a, b) -> update());
        remaining = session.retryInSeconds();
        update();
    }

    private void update() {
        boolean signedIn = session.currentUser() != null && !session.isSigningOut();
        ConnectionState state = session.getConnectionState();
        switch (state) {
            case RECONNECTING -> {
                showProblem(Severity.WARNING);
                refreshCountdownText();
                if (countdown.getStatus() != Animation.Status.RUNNING) countdown.play();
            }
            case OFFLINE -> {
                if (signedIn) {
                    countdown.stop();
                    showProblem(Severity.ERROR);
                    setText("Can't reach server");
                } else hide();
            }
            case CONNECTED -> {
                countdown.stop();
                if (problemShown) showBackOnline();
                else hide();
            }
            case CONNECTING -> {
                if (!problemShown) hide();
            }
        }
    }

    private void showProblem(Severity severity) {
        backOnline.stop();
        problemShown = true;
        setSeverity(severity);
        setAction("Retry now", retry);
        show();
    }

    private void showBackOnline() {
        problemShown = false;
        setSeverity(Severity.SUCCESS);
        setText("Back online");
        clearAction();
        show();
        backOnline.playFromStart();
    }

    private void tick() {
        if (remaining > 0) remaining--;
        refreshCountdownText();
    }

    private void refreshCountdownText() {
        setText(remaining > 0 ? "Reconnecting in " + remaining + "s..." : "Reconnecting...");
    }

    private void show() { setVisible(true); setManaged(true); }

    private void hide() {
        countdown.stop();
        backOnline.stop();
        problemShown = false;
        setVisible(false);
        setManaged(false);
    }
}
