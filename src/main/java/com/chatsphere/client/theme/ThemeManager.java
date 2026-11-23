package com.chatsphere.client.theme;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Owns the stylesheet list of every Scene: base.css, components.css, theme-(mode).css.
 * Views add their own stylesheets (auth.css, ...) to their own root, not to the Scene, so apply() never drops them.
 * The chosen mode is persisted with java.util.prefs (key "theme").
 */
public final class ThemeManager {
    private static final Logger LOG = Logger.getLogger(ThemeManager.class.getName());
    private static final String PREF_KEY = "theme";
    private static final String CSS_DIR = "/com/chatsphere/client/css/";

    private static final ObjectProperty<ThemeMode> MODE = new SimpleObjectProperty<>(load());
    private static final List<WeakReference<Scene>> SCENES = new ArrayList<>();

    private ThemeManager() {}

    /** Resolves a stylesheet in the client css folder, failing loudly if the file is missing. */
    public static String css(String fileName) {
        var url = ThemeManager.class.getResource(CSS_DIR + fileName);
        return Objects.requireNonNull(url, "Missing stylesheet " + CSS_DIR + fileName).toExternalForm();
    }

    /** Sets base + components + the given theme on the scene, keeping any other stylesheet already there. */
    public static void apply(Scene scene, ThemeMode mode) {
        String base = css("base.css"), components = css("components.css");
        List<String> sheets = new ArrayList<>(scene.getStylesheets());
        for (ThemeMode m : ThemeMode.values()) sheets.remove(css(m.cssFile()));
        if (!sheets.contains(components)) sheets.add(0, components);
        if (!sheets.contains(base)) sheets.add(0, base);
        sheets.add(css(mode.cssFile()));
        scene.getStylesheets().setAll(sheets);   // one change, one CSS pass
    }

    /** Applies the current mode to the scene and keeps it in sync when the mode changes. */
    public static void bind(Scene scene) {
        SCENES.add(new WeakReference<>(scene));
        apply(scene, MODE.get());
    }

    public static ThemeMode mode() { return MODE.get(); }
    public static ObjectProperty<ThemeMode> modeProperty() { return MODE; }

    /** Applies live to every bound scene and saves the choice. FX thread. */
    public static void setMode(ThemeMode mode) {
        MODE.set(mode);
        SCENES.removeIf(r -> r.get() == null);
        for (WeakReference<Scene> r : SCENES) {
            Scene s = r.get();
            if (s != null) apply(s, mode);
        }
        try {
            Preferences.userNodeForPackage(ThemeManager.class).put(PREF_KEY, mode.name());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not save theme preference", e);
        }
    }

    private static ThemeMode load() {
        try {
            String v = Preferences.userNodeForPackage(ThemeManager.class).get(PREF_KEY, ThemeMode.LIGHT.name());
            return ThemeMode.valueOf(v);
        } catch (RuntimeException e) {
            return ThemeMode.LIGHT;
        }
    }
}
