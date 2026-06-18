package org.example.app.gui.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Opens a file or folder in the OS file manager / default application.
 *
 * <p>Uses the platform opener via {@link ProcessBuilder} rather than
 * {@code java.awt.Desktop}: mixing AWT with JavaFX on Linux/GTK installs
 * conflicting X11 error handlers ("XSetErrorHandler() called with a GDK error
 * trap pushed") and crashes the app.
 */
public final class FileOpener {

    private FileOpener() {}

    public static void open(Path path) {
        if (path == null) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> command;
        if (os.contains("mac")) {
            command = List.of("open", path.toString());
        } else if (os.contains("win")) {
            command = List.of("cmd", "/c", "start", "", path.toString());
        } else {
            command = List.of("xdg-open", path.toString());
        }
        try {
            new ProcessBuilder(command).start();
        } catch (IOException ignored) {}
    }
}
