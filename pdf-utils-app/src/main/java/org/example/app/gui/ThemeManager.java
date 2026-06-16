package org.example.app.gui;

import javafx.scene.Scene;

import java.util.prefs.Preferences;

public class ThemeManager {

    /**
     * Available colour palettes. SYSTEM is special — it follows the OS light/dark
     * setting and maps to LIGHT or DARK. The rest are concrete skins.
     */
    public enum Theme {
        SYSTEM   ("System",    null),
        LIGHT    ("Daylight",  "/css/light.css"),
        DARK     ("Graphite",  "/css/dark.css"),
        NORD     ("Nord",      "/css/nord.css"),
        DRACULA  ("Dracula",   "/css/dracula.css"),
        SOLARIZED("Solarized", "/css/solarized.css"),
        SUNSET   ("Sunset",    "/css/sunset.css");

        public final String displayName;
        private final String css;

        Theme(String displayName, String css) {
            this.displayName = displayName;
            this.css = css;
        }
    }

    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);
    private static Theme current = readStored();

    private static Theme readStored() {
        try {
            return Theme.valueOf(PREFS.get("theme", "SYSTEM"));
        } catch (IllegalArgumentException e) {
            return Theme.SYSTEM;
        }
    }

    public static Theme getCurrent() { return current; }

    public static void apply(Scene scene, Theme theme) {
        current = theme;
        PREFS.put("theme", theme.name());
        scene.getStylesheets().setAll(resolveUrl(theme));
    }

    public static void applyStored(Scene scene) {
        apply(scene, current);
    }

    private static String resolveUrl(Theme theme) {
        Theme effective = theme == Theme.SYSTEM ? detectSystem() : theme;
        return ThemeManager.class.getResource(effective.css).toExternalForm();
    }

    private static Theme detectSystem() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? detectWindows() : detectLinux();
    }

    private static Theme detectWindows() {
        try {
            Process p = new ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme"
            ).start();
            String out = new String(p.getInputStream().readAllBytes());
            return out.contains("0x0") ? Theme.DARK : Theme.LIGHT;
        } catch (Exception e) {
            return Theme.LIGHT;
        }
    }

    private static Theme detectLinux() {
        try {
            Process p = new ProcessBuilder(
                "gsettings", "get", "org.gnome.desktop.interface", "color-scheme"
            ).start();
            String out = new String(p.getInputStream().readAllBytes()).trim().toLowerCase();
            return out.contains("dark") ? Theme.DARK : Theme.LIGHT;
        } catch (Exception e) {
            return Theme.LIGHT;
        }
    }
}
