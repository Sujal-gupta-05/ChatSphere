package com.chatsphere.client.nav;

/** A page that holds resources (event listeners, timers). The SceneNavigator calls dispose() when it is replaced. */
public interface Disposable {
    void dispose();
}
