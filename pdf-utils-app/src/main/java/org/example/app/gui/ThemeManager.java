package org.example.app.gui;

import javafx.scene.Scene;

import java.util.prefs.Preferences;

public class ThemeManager {

    public enum Theme { LIGHT, DARK, SYSTEM }

    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);
    private static Theme current = Theme.valueOf(PREFS.get("theme", "SYSTEM"));

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
        String file = effective == Theme.DARK ? "/css/dark.css" : "/css/light.css";
        return ThemeManager.class.getResource(file).toExternalForm();
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
