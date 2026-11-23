package com.chatsphere.client.theme;

/** Light and Dark only (JavaFX 21 cannot detect the OS theme). */
public enum ThemeMode {
    LIGHT("theme-light.css"), DARK("theme-dark.css");

    private final String cssFile;
    ThemeMode(String cssFile) { this.cssFile = cssFile; }
    public String cssFile() { return cssFile; }
}
